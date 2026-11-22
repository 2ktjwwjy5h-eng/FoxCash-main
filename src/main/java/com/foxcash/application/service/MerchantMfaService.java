package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.model.merchant.MerchantMfaMethod;
import com.foxcash.domain.model.merchant.MerchantRecoveryCode;
import com.foxcash.domain.model.merchant.MerchantTotp;
import com.foxcash.domain.port.in.MerchantMfaUseCase;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
import com.foxcash.domain.port.out.MerchantMfaMethodRepository;
import com.foxcash.domain.port.out.MerchantRecoveryCodeRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantTotpRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.service.Totp;
import com.foxcash.infrastructure.security.CryptoConverter;
import com.foxcash.infrastructure.security.DocumentHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicação: gestão do MFA do lojista no portal (Spec 94) — ativação em 2
 * passos (prova de posse do app), desativação com posse (senha do portal +
 * TOTP/recovery), regeneração de recovery codes e verificação do 2º fator no
 * login. Espelho do {@link MasterMfaService} (Spec 44) com actor
 * {@code MERCHANT} e senha validada via {@code MerchantCredential} (Spec 20/39).
 *
 * <p><b>Segurança (decisões espelho do master, 2026-08-06):</b> secret TOTP
 * criptografado em repouso (CryptoConverter AES-256/GCM — hash puro tornaria a
 * verificação impossível); recovery codes SHA-256 single-use (consume atômico
 * SEC-20); comparações em tempo constante ({@code MessageDigest.isEqual});
 * auditoria Spec 41 com os 7 eventos MFA (sem {@code MFA_UNLOCKED} — o lockout
 * expira sozinho).
 */
@Service
public class MerchantMfaService implements MerchantMfaUseCase {

    /** Recovery codes gerados na ativação/regeneração. */
    private static final int RECOVERY_COUNT = 8;

    /** TTL do estado PENDING (confirmar a posse do app). */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    /** Máximo de falhas de TOTP antes do lockout (mesmo padrão do login B-06). */
    private static final int MAX_TOTP_ATTEMPTS = 5;

    /** Duração do lockout de TOTP (expira sozinho — sem unlock manual). */
    private static final Duration TOTP_LOCKOUT = Duration.ofMinutes(15);

    private final MerchantRepository merchantRepository;
    private final MerchantCredentialRepository credentialRepository;
    private final MerchantMfaMethodRepository methodRepository;
    private final MerchantTotpRepository totpRepository;
    private final MerchantRecoveryCodeRepository recoveryCodeRepository;
    private final PasswordHasher passwordHasher;
    private final CryptoConverter cryptoConverter;
    private final MerchantTotpFailurePersister failurePersister;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public MerchantMfaService(MerchantRepository merchantRepository,
                              MerchantCredentialRepository credentialRepository,
                              MerchantMfaMethodRepository methodRepository,
                              MerchantTotpRepository totpRepository,
                              MerchantRecoveryCodeRepository recoveryCodeRepository,
                              PasswordHasher passwordHasher,
                              CryptoConverter cryptoConverter,
                              MerchantTotpFailurePersister failurePersister,
                              PlatformActivityRecorder activityRecorder,
                              Clock clock) {
        this.merchantRepository = merchantRepository;
        this.credentialRepository = credentialRepository;
        this.methodRepository = methodRepository;
        this.totpRepository = totpRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordHasher = passwordHasher;
        this.cryptoConverter = cryptoConverter;
        this.failurePersister = failurePersister;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ActivateResult activate(UUID merchantId) {
        Merchant merchant = requireMerchant(merchantId);
        // Não pode haver método ativo nem pendente para ativar de novo.
        if (methodRepository.findActiveByMerchantId(merchantId).isPresent()) {
            throw new IllegalStateException("MFA ja ativado");
        }
        methodRepository.findPendingByMerchantId(merchantId).ifPresent(m ->
                methodRepository.deleteByMerchantId(merchantId));

        Instant now = clock.instant();
        UUID methodId = UUID.randomUUID();
        String secret = Totp.generateSecret();

        MerchantMfaMethod method = MerchantMfaMethod.pending(methodId, merchantId, now, now.plus(PENDING_TTL));
        methodRepository.save(method);
        // Secret criptografado em repouso (AES-256/GCM) — o domínio guarda o
        // ciphertext; a decifração acontece apenas na verificação do código.
        totpRepository.save(MerchantTotp.create(methodId, cryptoConverter.convertToDatabaseColumn(secret), now));

        return new ActivateResult(Totp.otpauthUri(merchant.name(), secret), secret);
    }

    @Override
    @Transactional
    public ConfirmResult confirm(UUID merchantId, String code) {
        MerchantMfaMethod method = methodRepository.findPendingByMerchantId(merchantId)
                .orElseThrow(InvalidMfaCodeException::new);
        Instant now = clock.instant();
        if (method.isPendingExpired(now)) {
            methodRepository.deleteByMerchantId(merchantId);
            throw new InvalidMfaCodeException();
        }
        MerchantTotp totp = totpRepository.findByMethodId(method.id())
                .orElseThrow(InvalidMfaCodeException::new);
        String secret = cryptoConverter.convertToEntityAttribute(totp.secretEncrypted());
        if (!Totp.verify(secret, code, now)) {
            throw new InvalidMfaCodeException();
        }

        methodRepository.save(method.activate(now));
        List<String> recoveryCodes = generateRecoveryCodes(method.id(), now);

        activityRecorder.record(PlatformActorType.MERCHANT, merchantId, PlatformAction.MFA_ACTIVATED,
                "MERCHANT", merchantId, merchantId, null);
        return new ConfirmResult(recoveryCodes);
    }

    @Override
    @Transactional
    public void deactivate(UUID merchantId, String code, String password) {
        requireMerchant(merchantId);
        // Posse 1: senha atual do portal (BCrypt — Spec 20/39). Senha incorreta
        // ou credencial ausente → 401 uniforme (sem oráculo).
        Optional<MerchantCredential> credential = credentialRepository.findByMerchantId(merchantId);
        if (credential.isEmpty() || !passwordHasher.matches(password, credential.get().passwordHash())) {
            throw new InvalidMfaCodeException();
        }
        verifySecondFactor(merchantId, code);

        methodRepository.deleteByMerchantId(merchantId);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId, PlatformAction.MFA_DEACTIVATED,
                "MERCHANT", merchantId, merchantId, null);
    }

    @Override
    @Transactional
    public List<String> regenerateRecoveryCodes(UUID merchantId, String code) {
        verifySecondFactor(merchantId, code);
        MerchantMfaMethod method = methodRepository.findActiveByMerchantId(merchantId)
                .orElseThrow(InvalidMfaCodeException::new);

        recoveryCodeRepository.deleteByMethodId(method.id());
        List<String> codes = generateRecoveryCodes(method.id(), clock.instant());

        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.MFA_RECOVERY_REGENERATED, "MERCHANT", merchantId, merchantId, null);
        return codes;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveMfa(UUID merchantId) {
        return methodRepository.findActiveByMerchantId(merchantId).isPresent();
    }

    /**
     * Verifica o 2º fator (login): TOTP válido (janela ±1, tempo constante,
     * anti-replay) OU recovery code single-use. Aplica lockout de TOTP (5 falhas
     * → 15min, expira sozinho) com 401 uniforme (sem oráculo). Auditoria
     * MFA_LOGIN_SUCCESS/FAILED/LOCKED. Espelho do
     * {@code MasterMfaService.verifySecondFactor} (Spec 44).
     */
    @Transactional
    public void verifySecondFactor(UUID merchantId, String code) {
        MerchantMfaMethod method = methodRepository.findActiveByMerchantId(merchantId)
                .orElseThrow(InvalidMfaCodeException::new);
        Instant now = clock.instant();
        MerchantTotp totp = totpRepository.findByMethodId(method.id())
                .orElseThrow(InvalidMfaCodeException::new);

        // Lockout ativo → 401 uniforme (expira sozinho — sem unlock manual).
        if (totp.isLocked(now)) {
            throw new InvalidMfaCodeException();
        }

        // 1) Tenta recovery code primeiro (tempo constante via hash).
        if (code != null && code.startsWith("mrc_")) {
            String hash = DocumentHasher.hash(code);
            // SEC-20 (revisão de segurança 2026-08-11): consume ATÔMICO (UPDATE
            // condicional no banco) — marca o código como usado SOMENTE se ainda
            // estiver sem uso (mesmo padrão do master).
            if (recoveryCodeRepository.consumeIfOpen(method.id(), hash, now)) {
                activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                        PlatformAction.MFA_RECOVERY_USED, "MERCHANT", merchantId, merchantId, null);
                activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                        PlatformAction.MFA_LOGIN_SUCCESS, "MERCHANT", merchantId, merchantId, null);
                totpRepository.save(totp.recordValidCode(null, now));
                return;
            }
        }

        // 2) TOTP: verifica o código (tempo constante, janela ±1) + anti-replay.
        // Código ausente (login 2FA incompleto) → mesmo caminho de falha: 401
        // uniforme, sem NPE no hash (409 vazaria que o código faltou — oráculo).
        String secret = cryptoConverter.convertToEntityAttribute(totp.secretEncrypted());
        String codeHash = (code == null) ? null : DocumentHasher.hash(code);
        boolean replay = totp.lastCodeHash() != null && codeHash != null
                && MessageDigest.isEqual(totp.lastCodeHash().getBytes(StandardCharsets.UTF_8),
                        codeHash.getBytes(StandardCharsets.UTF_8));
        if (replay || !Totp.verify(secret, code, now)) {
            // O persister grava a falha/lockout em transação PRÓPRIA
            // (REQUIRES_NEW) — o throw abaixo reverteria o save/evento comuns na
            // transação do login, tornando o lockout de TOTP inócuo. SEC-01: o
            // persister re-lê a linha com FOR UPDATE e re-aplica a falha sobre o
            // estado FRESCO — incremento atômico (sem lost update).
            failurePersister.recordFailure(merchantId, method.id(), now,
                    MAX_TOTP_ATTEMPTS, TOTP_LOCKOUT.toMinutes());
            throw new InvalidMfaCodeException();
        }

        totpRepository.save(totp.recordValidCode(codeHash, now));
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId, PlatformAction.MFA_LOGIN_SUCCESS,
                "MERCHANT", merchantId, merchantId, null);
    }

    private List<String> generateRecoveryCodes(UUID methodId, Instant now) {
        List<MerchantRecoveryCode> codes = new ArrayList<>();
        List<String> plaintext = new ArrayList<>();
        for (int i = 0; i < RECOVERY_COUNT; i++) {
            String code = "mrc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            codes.add(MerchantRecoveryCode.create(UUID.randomUUID(), methodId,
                    DocumentHasher.hash(code), now));
            plaintext.add(code);
        }
        recoveryCodeRepository.saveAll(codes);
        return plaintext;
    }

    private Merchant requireMerchant(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
    }
}
