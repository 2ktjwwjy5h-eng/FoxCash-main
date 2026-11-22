package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidCredentialsException;
import com.foxcash.domain.exception.MerchantLoginDeniedException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.model.merchant.MerchantSession;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.port.in.MerchantPortalAuthUseCase;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantSessionRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import com.foxcash.infrastructure.config.AuthProperties;
import com.foxcash.infrastructure.security.DummyCredentials;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Aplicação: autenticação do lojista no portal (Spec 20).
 *
 * <p>Login valida **e-mail** + senha BCrypt (credencial criada no cadastro com a
 * senha escolhida pelo usuário — Spec 39), exige lojista {@code ACTIVE}, e emite
 * sessão com token opaco (somente o hash SHA-256 persistido). Logout revoga a
 * sessão pelo hash do token apresentado. Mesmo padrão do {@link MasterAuthService}
 * (Spec 13), com role própria ({@code ROLE_MERCHANT_PORTAL}).
 */
@Service
public class MerchantPortalAuthService implements MerchantPortalAuthUseCase {

    private final MerchantRepository merchantRepository;
    private final MerchantCredentialRepository credentialRepository;
    private final MerchantSessionRepository sessionRepository;
    private final PasswordHasher passwordHasher;
    private final SessionTokenGenerator tokenGenerator;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;
    private final Duration sessionTtl;
    private final AuthProperties authProperties;
    private final MerchantLoginLockoutPersister lockoutPersister;
    private final SecurityAlertPublisher securityAlerts;
    private final MerchantMfaService mfaService;
    private final DeviceFingerprintService fingerprintService;

    public MerchantPortalAuthService(MerchantRepository merchantRepository,
                                     MerchantCredentialRepository credentialRepository,
                                     MerchantSessionRepository sessionRepository,
                                     PasswordHasher passwordHasher,
                                     SessionTokenGenerator tokenGenerator,
                                     PlatformActivityRecorder activityRecorder,
                                     Clock clock,
                                     @Qualifier("merchantSessionTtl") Duration merchantSessionTtl,
                                     AuthProperties authProperties,
                                     MerchantLoginLockoutPersister lockoutPersister,
                                     SecurityAlertPublisher securityAlerts,
                                     MerchantMfaService mfaService,
                                     DeviceFingerprintService fingerprintService) {
        this.merchantRepository = merchantRepository;
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
        this.passwordHasher = passwordHasher;
        this.tokenGenerator = tokenGenerator;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
        this.sessionTtl = merchantSessionTtl;
        this.authProperties = authProperties;
        this.lockoutPersister = lockoutPersister;
        this.securityAlerts = securityAlerts;
        this.mfaService = mfaService;
        this.fingerprintService = fingerprintService;
    }

    @Override
    @Transactional
    public LoginResult login(LoginCommand command) {
        // Identificador de login desde a Spec 39: e-mail (normalizado em minúsculas).
        Merchant merchant = merchantRepository.findByEmail(command.email().trim().toLowerCase())
                .orElse(null);

        if (merchant == null) {
            // Anti-enumeração (auditoria 2026-08-05, B-01): executa um BCrypt dummy para o
            // tempo de resposta ser uniforme entre e-mail existente e inexistente; a
            // resposta é sempre 401 (mesma de senha incorreta).
            passwordHasher.matches(command.password(), DummyCredentials.DUMMY_BCRYPT_HASH);
            // Spec 64: falha de login (e-mail inexistente) → contador de rajada.
            securityAlerts.recordLoginFailure("merchant");
            throw new InvalidCredentialsException();
        }

        MerchantCredential credential = credentialRepository.findByMerchantId(merchant.id())
                .orElseThrow(() -> {
                    // SEC-04 (revisão de segurança 2026-08-11): credencial ausente
                    // também executa o BCrypt dummy — o tempo de resposta fica
                    // uniforme com senha incorreta (sem oráculo de existência).
                    passwordHasher.matches(command.password(), DummyCredentials.DUMMY_BCRYPT_HASH);
                    // Spec 64: falha de login (sem credencial) → contador de rajada.
                    securityAlerts.recordLoginFailure("merchant");
                    return new InvalidCredentialsException();
                });

        Instant now = clock.instant();
        // Senha PRIMEIRO (anti-enumeração + SEC-04): o BCrypt roda ANTES do check
        // de lockout — conta bloqueada e senha incorreta respondem no MESMO tempo
        // (~BCrypt). Antes, o check de lockout antes do BCrypt fazia a conta
        // bloqueada responder ~1ms (oráculo de timing para o atacante detectar o
        // fim da janela e retomar o brute-force).
        if (!passwordHasher.matches(command.password(), credential.passwordHash())) {
            // B-06: falha seguidas de senha incrementam o contador da credencial; ao
            // atingir o limite (max-login-attempts) a conta fica bloqueada por
            // lockout-minutes (401 uniforme).
            credential.registerFailedLogin(now, authProperties.getMaxLoginAttempts(),
                    Duration.ofMinutes(authProperties.getLockoutMinutes()));
            // REQUIRES_NEW (B-06): o throw abaixo faria rollback de um save comum no
            // @Transactional do login — a falha precisa ser persistida mesmo com o
            // rollback da ação (padrão MasterLoginLockoutPersister, B-08).
            // SEC-01: o persister re-lê a linha com FOR UPDATE e re-aplica a falha
            // sobre o estado FRESCO — incremento atômico (sem lost update entre
            // tentativas paralelas, o que burlaria o lockout).
            lockoutPersister.record(merchant.id(), now,
                    authProperties.getMaxLoginAttempts(),
                    Duration.ofMinutes(authProperties.getLockoutMinutes()));
            securityAlerts.recordLoginFailure("merchant");
            throw new InvalidCredentialsException();
        }

        // SEC-15 (revisão de segurança 2026-08-11): o check de lockout e o reset
        // do contador acontecem sobre o estado FRESCO do banco (FOR UPDATE), não
        // sobre a snapshot carregada no início do request. Sem a re-leitura, um
        // login VÁLIDO que carregou a credencial antes de uma falha concorrente
        // aplicar o lockout salvaria a snapshot stale (contador/lockedUntil
        // antigos) por cima do lockout recém-aplicado — lost update inverso do
        // SEC-01 que burlaria a janela. A conta bloqueada com senha correta
        // também responde 401 (o lockout prevalece; o tempo já foi uniformizado
        // pelo BCrypt — SEC-04).
        MerchantCredential fresh = credentialRepository.findByMerchantIdForUpdate(merchant.id())
                .orElseThrow(InvalidCredentialsException::new);
        if (fresh.isLocked(now)) {
            securityAlerts.recordLoginFailure("merchant");
            throw new InvalidCredentialsException();
        }

        if (merchant.status() != MerchantStatus.ACTIVE) {
            throw new MerchantLoginDeniedException(merchant.id());
        }

        // Spec 94 (2FA): com MFA ativo, a senha NÃO basta — exige o 2º fator
        // (TOTP ou recovery code). 401 uniforme sem código/código inválido.
        // Espelho do MasterAuthService (Spec 44).
        if (mfaService.hasActiveMfa(merchant.id())) {
            mfaService.verifySecondFactor(merchant.id(), command.totpCode());
        }

        // Login válido: limpa contador/lockout e registra o último uso (estado FRESCO).
        fresh.registerSuccessfulLogin();
        fresh.recordUse(now);
        credentialRepository.save(fresh);

        SessionTokenGenerator.TokenPair token = tokenGenerator.generate();
        Instant expiresAt = now.plus(sessionTtl);
        MerchantSession session = MerchantSession.issue(merchant.id(), token.hash(), now, expiresAt);
        sessionRepository.save(session);

        Map<String, Object> details = new HashMap<>();
        details.put("email", merchant.email()); // null aceito (legado/restore sem email)
        // Spec 141: fingerprint do navegador (sinal de risco PASSIVO, best-effort).
        fingerprintService.record(command.fingerprintHash(), merchant.id())
                .ifPresent(fp -> details.put("fingerprint",
                        Map.of("hash", fp.fingerprintHash(), "distinctMerchants", fp.distinctMerchants())));
        activityRecorder.record(PlatformActorType.MERCHANT, merchant.id(), PlatformAction.MERCHANT_LOGIN,
                "SESSION", session.id(), merchant.id(), details);

        return new LoginResult(token.plaintext(), merchant.id(), merchant.name(), expiresAt);
    }

    @Override
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        sessionRepository.findByTokenHash(tokenGenerator.hash(rawToken))
                .ifPresent(session -> {
                    session.revoke(clock.instant());
                    sessionRepository.save(session);
                    activityRecorder.record(PlatformActorType.MERCHANT, session.merchantId(),
                            PlatformAction.MERCHANT_LOGOUT, "SESSION", session.id(),
                            session.merchantId(), null);
                });
    }
}
