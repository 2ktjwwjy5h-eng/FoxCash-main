package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidResetTokenException;
import com.foxcash.domain.exception.ResetTokenAlreadyOpenException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.auth.PasswordResetToken;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.port.in.PasswordResetUseCase;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantSessionRepository;
import com.foxcash.domain.port.out.PasswordResetTokenRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.port.out.ResetTokenDeliverer;
import com.foxcash.domain.port.out.ResetTokenGenerator;
import com.foxcash.domain.service.EmailValidator;
import com.foxcash.domain.service.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Aplicação: recuperação de senha do lojista (Spec 40).
 *
 * <p>{@code forgot} emite um token one-time {@code rst_<32 hex>} (apenas o hash
 * SHA-256 é persistido, expira em {@code reset-token-ttl-seconds}, um ativo por
 * lojista). {@code reset} consome o token, redefine a senha (BCrypt) e revoga as
 * sessões ativas do lojista — tudo na mesma transação.
 */
@Service
public class PasswordResetService implements PasswordResetUseCase {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** E-mail/destino dummy para uniformizar o tempo do caminho inexistente (anti-enumeração). */
    private static final String DUMMY_EMAIL = "nao-existe@foxcash.invalid";

    private final MerchantRepository merchantRepository;
    private final MerchantCredentialRepository credentialRepository;
    private final MerchantSessionRepository sessionRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final ResetTokenGenerator resetTokenGenerator;
    private final PasswordHasher passwordHasher;
    private final PlatformActivityRecorder activityRecorder;
    private final ResetTokenDeliverer resetTokenDeliverer;
    private final Clock clock;
    private final Duration resetTokenTtl;

    public PasswordResetService(MerchantRepository merchantRepository,
                                MerchantCredentialRepository credentialRepository,
                                MerchantSessionRepository sessionRepository,
                                PasswordResetTokenRepository resetTokenRepository,
                                ResetTokenGenerator resetTokenGenerator,
                                PasswordHasher passwordHasher,
                                PlatformActivityRecorder activityRecorder,
                                ResetTokenDeliverer resetTokenDeliverer,
                                Clock clock,
                                @Qualifier("resetTokenTtl") Duration resetTokenTtl) {
        this.merchantRepository = merchantRepository;
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.resetTokenGenerator = resetTokenGenerator;
        this.passwordHasher = passwordHasher;
        this.activityRecorder = activityRecorder;
        this.resetTokenDeliverer = resetTokenDeliverer;
        this.clock = clock;
        this.resetTokenTtl = resetTokenTtl;
    }

    @Override
    @Transactional
    public ForgotResult forgot(ForgotCommand command) {
        String email = normalizeEmail(command.email());
        Optional<Merchant> merchant = merchantRepository.findByEmail(email);
        if (merchant.isEmpty()) {
            // Resposta uniforme (anti-enumeração): quem decide expor o token é o
            // controller via foxcash.auth.expose-reset-token (dev=true, prod=false).
            // Spec 45: o caminho inexistente executa uma entrega DUMMY (padrão do
            // dummy BCrypt — B-01) para uniformizar o tempo de resposta; a falha é
            // logada e nunca derruba o fluxo (204 sempre).
            deliverOrLog(DUMMY_EMAIL, "rst_" + "0".repeat(32), clock.instant().plus(resetTokenTtl));
            return ForgotResult.empty();
        }
        // Um único token ativo por lojista: apaga o aberto antes de inserir.
        resetTokenRepository.deleteOpenByMerchantId(merchant.get().id());
        ResetTokenGenerator.TokenPair token = resetTokenGenerator.generate();
        try {
            resetTokenRepository.save(PasswordResetToken.issue(
                    merchant.get().id(), token.hash(), clock.instant(), resetTokenTtl));
        } catch (DataIntegrityViolationException e) {
            // Corrida: o índice único parcial uq_password_reset_open_per_merchant
            // rejeitou o novo token (outro forgot concorrente) → 409.
            throw new ResetTokenAlreadyOpenException();
        }
        // Spec 45: entrega o token (SMTP em prod / log em dev). Falha de e-mail
        // não derruba o fluxo — o pedido segue e o lojista pode tentar de novo
        // (o token antigo é invalidado pelo próximo forgot — Spec 40).
        deliverOrLog(merchant.get().email(), token.plaintext(),
                clock.instant().plus(resetTokenTtl));
        return ForgotResult.of(token.plaintext());
    }

    /**
     * Entrega o token via {@link ResetTokenDeliverer}, logando falha sem relançar
     * (o forgot-password responde 204 mesmo com SMTP indisponível — Spec 45).
     */
    private void deliverOrLog(String email, String resetToken, Instant expiresAt) {
        try {
            resetTokenDeliverer.deliver(email, resetToken, expiresAt);
        } catch (RuntimeException e) {
            log.warn("Falha ao entregar token de reset (resposta segue 204): {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void reset(ResetCommand command) {
        requireNewPassword(command.newPassword());
        String tokenHash = resetTokenGenerator.hash(command.token());
        PasswordResetToken token = resetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidResetTokenException::new);
        if (!token.isValid(clock.instant())) {
            // Expirado ou já usado — mesmo código (sem oráculo).
            throw new InvalidResetTokenException();
        }
        Instant now = clock.instant();
        // SEC-10 (revisão de segurança 2026-08-11): consume ATÔMICO — marca o
        // token como usado somente se ainda estiver aberto e não expirado
        // (UPDATE condicional no banco). Dois reset concorrentes com o MESMO
        // token: apenas o primeiro consome; o segundo falha com o mesmo 400
        // (single-use de verdade — antes era leitura + save, e ambos passavam).
        if (!resetTokenRepository.consumeIfOpen(tokenHash, now)) {
            throw new InvalidResetTokenException();
        }
        MerchantCredential credential = credentialRepository.findByMerchantId(token.merchantId())
                .orElseThrow(InvalidResetTokenException::new);

        // Senha nova (BCrypt) + token consumido + sessões revogadas: atômico.
        credential.changePassword(passwordHasher.hash(command.newPassword()), now);
        credentialRepository.save(credential);
        sessionRepository.revokeAllByMerchantId(token.merchantId(), now);

        // Spec 41: relatório de atividades — senha redefinida (sem a senha em claro).
        activityRecorder.record(PlatformActorType.MERCHANT, token.merchantId(), PlatformAction.PASSWORD_RESET,
                "CREDENTIAL", credential.id(), token.merchantId(), null);
    }

    private String normalizeEmail(String email) {
        if (!EmailValidator.isValid(email)) {
            throw new IllegalArgumentException("e-mail inválido");
        }
        return email.trim().toLowerCase();
    }

    /** Politica de senha unica (auditoria 2026-08-05/B-07): 8-72 + complexidade. */
    private void requireNewPassword(String password) {
        PasswordPolicy.validate(password);
    }
}
