package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidCredentialsException;
import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.exception.MerchantLoginDeniedException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.model.merchant.MerchantSession;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.in.MerchantPortalAuthUseCase;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantSessionRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da autenticacao do lojista no portal (Spec 20): login valida
 * e-mail + senha BCrypt (identificador desde a Spec 39), exige lojista ACTIVE,
 * emite sessao com token opaco
 * (apenas o hash persistido); logout revoga.
 */
class MerchantPortalAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String DOCUMENT = "52998224725";
    private static final String EMAIL = "loja@example.com";
    private static final String PASSWORD = "foxcash_0123456789ab";
    private static final String PASSWORD_HASH = "$2a$10$bcryptfakehash";
    /** Mesmo hash dummy do serviço (anti-enumeração por timing) — deve ser um BCrypt válido. */
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MerchantCredentialRepository credentialRepository = mock(MerchantCredentialRepository.class);
    private final MerchantSessionRepository sessionRepository = mock(MerchantSessionRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final SessionTokenGenerator tokenGenerator = mock(SessionTokenGenerator.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Duration sessionTtl = Duration.ofHours(8);

    private MerchantPortalAuthService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final MerchantLoginLockoutPersister lockoutPersister = mock(MerchantLoginLockoutPersister.class);
    private final SecurityAlertPublisher securityAlerts = mock(SecurityAlertPublisher.class);
    private final MerchantMfaService mfaService = mock(MerchantMfaService.class);
    private final DeviceFingerprintService fingerprintService = mock(DeviceFingerprintService.class);

    @BeforeEach
    void setUp() {
        service = new MerchantPortalAuthService(
                merchantRepository, credentialRepository, sessionRepository,
                passwordHasher, tokenGenerator, activityRecorder, clock, sessionTtl,
                authProperties(), lockoutPersister, securityAlerts, mfaService,
                fingerprintService);
        // Spec 94: sem MFA ativo por default — o login atual fica inalterado
        // (o verifySecondFactor só roda quando hasActiveMfa() == true).
        when(mfaService.hasActiveMfa(any())).thenReturn(false);
        // Spec 141: fingerprint ausente no comando → serviço não grava (best-effort).
        when(fingerprintService.record(any(), any())).thenReturn(java.util.Optional.empty());
    }

    private static com.foxcash.infrastructure.config.AuthProperties authProperties() {
        com.foxcash.infrastructure.config.AuthProperties properties =
                new com.foxcash.infrastructure.config.AuthProperties();
        properties.setMaxLoginAttempts(5);
        properties.setLockoutMinutes(15);
        return properties;
    }

    private Merchant merchant(MerchantStatus status) {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, DOCUMENT,
                "hash", status, PLAN, Money.of("0.00"), false, NOW);
    }

    private MerchantCredential credential() {
        return MerchantCredential.restore(UUID.randomUUID(), MERCHANT_ID, PASSWORD_HASH, NOW, NOW, null);
    }

    @Test
    void loginWithValidCredentialsIssuesSession() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(tokenGenerator.generate()).thenReturn(
                new SessionTokenGenerator.TokenPair("foxcash-portal-token", "tokhash"));

        MerchantPortalAuthUseCase.LoginResult result =
                service.login(new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD));

        assertThat(result.token()).isEqualTo("foxcash-portal-token");
        assertThat(result.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(sessionTtl));
        // Round 11 (getters/validade da sessão emitida): NullReturnVals L61
        // (merchantId — sobrevivente), EmptyObjectReturnVals L65 (tokenHash),
        // NullReturnVals L69/L73 (createdAt/expiresAt) e NegateConditionals L53
        // (isValid exige o 2º operando avaliado — sessão NÃO revogada).
        ArgumentCaptor<MerchantSession> sessionCaptor = ArgumentCaptor.forClass(MerchantSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        MerchantSession issued = sessionCaptor.getValue();
        assertThat(issued.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(issued.tokenHash()).isEqualTo("tokhash");
        assertThat(issued.createdAt()).isEqualTo(NOW);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(sessionTtl));
        assertThat(issued.isValid(NOW)).isTrue();
        // Mutation (VoidMethodCall L121): recordUse removido → lastUsedAt não seria
        // gravado no login válido.
        ArgumentCaptor<MerchantCredential> credCaptor = ArgumentCaptor.forClass(MerchantCredential.class);
        verify(credentialRepository).save(credCaptor.capture());
        assertThat(credCaptor.getValue().lastUsedAt()).isEqualTo(NOW);
    }

    // Spec 94: MFA no login — com MFA ativo a senha NÃO basta (2º fator exigido);
    // sem MFA ativo o fluxo atual fica intacto (verifySecondFactor não roda).
    @Test
    void loginWithMfaActiveRequiresSecondFactor() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(mfaService.hasActiveMfa(MERCHANT_ID)).thenReturn(true);
        when(tokenGenerator.generate()).thenReturn(
                new SessionTokenGenerator.TokenPair("foxcash-portal-token", "tokhash"));

        MerchantPortalAuthUseCase.LoginResult result =
                service.login(new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD, "123456"));

        assertThat(result.token()).isEqualTo("foxcash-portal-token");
        // O 2º fator foi validado com o código informado (TOTP/recovery).
        verify(mfaService).verifySecondFactor(MERCHANT_ID, "123456");
    }

    @Test
    void loginWithMfaActiveAndInvalidCodeThrowsUnauthorized() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(mfaService.hasActiveMfa(MERCHANT_ID)).thenReturn(true);
        org.mockito.Mockito.doThrow(new InvalidMfaCodeException())
                .when(mfaService).verifySecondFactor(MERCHANT_ID, "000000");

        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD, "000000")))
                .isInstanceOf(InvalidMfaCodeException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void loginWithoutMfaDoesNotCallSecondFactor() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(mfaService.hasActiveMfa(MERCHANT_ID)).thenReturn(false);
        when(tokenGenerator.generate()).thenReturn(
                new SessionTokenGenerator.TokenPair("foxcash-portal-token", "tokhash"));

        service.login(new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD));

        verify(mfaService, never()).verifySecondFactor(any(), any());
    }

    @Test
    void loginWithWrongPasswordThrowsUnauthorized() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void loginWithUnknownDocumentThrowsUnauthorized() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginWithUnknownEmailRunsDummyBcryptToNormalizeTiming() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
        // O BCrypt dummy é executado para o tempo de resposta ser uniforme com
        // e-mail existente (anti-enumeração por timing).
        verify(passwordHasher).matches(eq(PASSWORD), anyString());
    }

    @Test
    void dummyBcryptHashIsValid() {
        // O hash dummy usado na anti-enumeração precisa ser um BCrypt VÁLIDO —
        // BCryptPasswordEncoder.matches lança IllegalArgumentException com hash
        // malformado (o que transformaria o 401 em 500 no login de e-mail inexistente).
        assertThat(new BCryptPasswordEncoder().matches("qualquer-senha", DUMMY_BCRYPT_HASH))
                .isFalse();
    }

    @Test
    void loginWithInactiveMerchantAndValidPasswordThrowsDenied() {
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.SUSPENDED)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                .isInstanceOf(MerchantLoginDeniedException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void loginWithInactiveMerchantAndWrongPasswordThrowsUnauthorizedWithoutRevealingStatus() {
        // Anti-enumeração: senha errada de conta inativa responde 401 (o status só
        // é revelado a quem conhece a senha correta).
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.SUSPENDED)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void logoutRevokesSessionByTokenHash() {
        MerchantSession session = MerchantSession.restore(
                UUID.randomUUID(), MERCHANT_ID, "tokhash", NOW, NOW.plus(sessionTtl), null);
        when(tokenGenerator.hash("raw-token")).thenReturn("tokhash");
        when(sessionRepository.findByTokenHash("tokhash")).thenReturn(Optional.of(session));

        service.logout("raw-token");

        assertThat(session.revokedAt()).isEqualTo(NOW);
        // Mutations (NullReturnVals L57 + BooleanTrueReturnVals L53): id exposto e
        // isValid falso após a revogação (o mutante de isValid → true passaria).
        assertThat(session.id()).isNotNull();
        assertThat(session.isValid(NOW)).isFalse();
        verify(sessionRepository).save(session);
    }

    // ---------- Auditoria 2026-08-05 (B-06): lockout por conta no login do lojista ----------

    @Test
    void repeatedFailedLoginsLockAccountAndEvenCorrectPasswordIsRejected() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE);
        MerchantCredential credential = credential();
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential));
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);

        // 5 falhas seguidas → lockout disparado (lockedUntil = NOW + 15min).
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(
                    new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(credential.isLocked(NOW)).isTrue();

        // Mesmo com a senha correta, durante a janela o login é recusado (401 uniforme).
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void successfulLoginClearsFailedAttemptsAndLockout() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE);
        MerchantCredential credential = credential();
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential));
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);

        // 2 falhas: contador incrementado, ainda sem lockout.
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.login(
                    new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(credential.failedLoginAttempts()).isEqualTo(2);
        assertThat(credential.isLocked(NOW)).isFalse();

        // Login válido limpa o contador (persistido antes da sessão).
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(tokenGenerator.generate()).thenReturn(
                new SessionTokenGenerator.TokenPair("foxcash-portal-token", "tokhash"));
        service.login(new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD));

        assertThat(credential.failedLoginAttempts()).isZero();
        assertThat(credential.lockedUntil()).isNull();
    }

    @Test
    void wrongPasswordPersistsFailureThroughRequiresNewPersister() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE);
        MerchantCredential credential = credential();
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);

        // O throw dentro do @Transactional faria rollback de um save comum — a
        // falha é persistida em transação PRÓPRIA (REQUIRES_NEW, padrão B-08).
        // SEC-01: o persister recebe merchantId + config (re-lê com FOR UPDATE).
        verify(lockoutPersister).record(MERCHANT_ID, NOW, 5, Duration.ofMinutes(15));
    }

    @Test
    void loginSucceedsAfterLockoutWindowExpires() {
        // Lockout expirado (lockedUntil no passado) + senha correta → login ok.
        MerchantCredential credential = MerchantCredential.restore(
                UUID.randomUUID(), MERCHANT_ID, PASSWORD_HASH, NOW, NOW, null, 0, NOW.minusSeconds(1));
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential));
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(credential));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        when(tokenGenerator.generate()).thenReturn(
                new SessionTokenGenerator.TokenPair("foxcash-portal-token", "tokhash"));

        MerchantPortalAuthUseCase.LoginResult result =
                service.login(new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD));

        assertThat(result.merchantId()).isEqualTo(MERCHANT_ID);
        verify(sessionRepository).save(any(MerchantSession.class));
    }

    // ---------- SEC-15 (revisão de segurança 2026-08-11): sucesso × lockout ----------

    @Test
    void successfulLoginDoesNotClearLockoutAppliedByConcurrentFailure() {
        // SEC-15: o reset do contador acontece sobre o estado FRESCO (FOR UPDATE).
        // A snapshot carregada no início do request pode estar SEM lockout, mas se
        // uma falha CONCORRENTE já aplicou o lockout no banco, o login válido NÃO
        // pode salvar a snapshot stale por cima — o lockout prevalece (401).
        when(merchantRepository.findByEmail(EMAIL)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(credential()));
        // O banco (estado fresco sob FOR UPDATE) já está bloqueado (5 falhas).
        when(credentialRepository.findByMerchantIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(
                MerchantCredential.restore(UUID.randomUUID(), MERCHANT_ID, PASSWORD_HASH,
                        NOW, NOW, null, 5, NOW.plusSeconds(900))));
        when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(
                new MerchantPortalAuthUseCase.LoginCommand(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);

        // O save NÃO acontece (a snapshot stale não pode apagar o lockout do banco).
        verify(credentialRepository, never()).save(any(MerchantCredential.class));
        verify(sessionRepository, never()).save(any());
    }
}
