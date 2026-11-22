package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.model.merchant.MerchantMfaMethod;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.merchant.MerchantTotp;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.in.MerchantMfaUseCase;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
import com.foxcash.domain.port.out.MerchantMfaMethodRepository;
import com.foxcash.domain.port.out.MerchantRecoveryCodeRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantTotpRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.service.Totp;
import com.foxcash.infrastructure.security.CryptoConverter;
import com.foxcash.infrastructure.security.CryptoProperties;
import com.foxcash.infrastructure.security.DocumentHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MerchantMfaService} (Spec 94): ativação em 2 passos
 * (activate → confirm com prova de posse), recovery codes single-use,
 * desativação com senha do portal + 2º fator, regeneração e verificação do 2º
 * fator (TOTP ±1 passo, anti-replay e lockout que expira sozinho — 401
 * uniforme). Espelho do {@code MasterMfaServiceTest} (Spec 44) com actor
 * {@code MERCHANT} e senha validada via {@code MerchantCredential}.
 */
class MerchantMfaServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID METHOD_ID = UUID.randomUUID();
    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuv";
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MerchantCredentialRepository credentialRepository = mock(MerchantCredentialRepository.class);
    private final MerchantMfaMethodRepository methodRepository = mock(MerchantMfaMethodRepository.class);
    private final MerchantTotpRepository totpRepository = mock(MerchantTotpRepository.class);
    private final MerchantRecoveryCodeRepository recoveryCodeRepository = mock(MerchantRecoveryCodeRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final CryptoConverter cryptoConverter =
            new CryptoConverter(properties("chave-de-teste-segura-32bytes!"));
    private final MerchantTotpFailurePersister failurePersister = mock(MerchantTotpFailurePersister.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);

    private MerchantMfaService service;
    private MerchantTotp currentTotp;

    @BeforeEach
    void setUp() {
        service = new MerchantMfaService(
                merchantRepository, credentialRepository, methodRepository, totpRepository,
                recoveryCodeRepository, passwordHasher, cryptoConverter, failurePersister,
                activityRecorder, Clock.fixed(NOW, ZoneOffset.UTC));
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant()));
        when(credentialRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(credential(PASSWORD_HASH)));
    }

    private static CryptoProperties properties(String secret) {
        CryptoProperties props = new CryptoProperties();
        props.setSecret(secret);
        return props;
    }

    private Merchant activeMerchant() {
        return Merchant.restore(MERCHANT_ID, "Loja Teste", "loja@example.com", null, null,
                MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, NOW.minusSeconds(60));
    }

    private MerchantCredential credential(String passwordHash) {
        return MerchantCredential.restore(UUID.randomUUID(), MERCHANT_ID, passwordHash,
                NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private MerchantMfaMethod pendingMethod() {
        return MerchantMfaMethod.pending(METHOD_ID, MERCHANT_ID, NOW, NOW.plus(Duration.ofMinutes(10)));
    }

    private MerchantMfaMethod activeMethod() {
        return MerchantMfaMethod.restore(METHOD_ID, MERCHANT_ID, "TOTP", "ACTIVE",
                NOW.minusSeconds(60), NOW.minusSeconds(30), null);
    }

    private MerchantTotp totp(String secret) {
        return MerchantTotp.restore(METHOD_ID, cryptoConverter.convertToDatabaseColumn(secret),
                null, 0, null);
    }

    /** Método ativo + TOTP com estado mutável (persistência simulada via holder). */
    private void stubActiveMfa(String secret) {
        currentTotp = totp(secret);
        when(methodRepository.findActiveByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(activeMethod()));
        when(totpRepository.findByMethodId(METHOD_ID)).thenAnswer(i -> Optional.of(currentTotp));
        when(totpRepository.save(any(MerchantTotp.class))).thenAnswer(i -> {
            currentTotp = i.getArgument(0);
            return currentTotp;
        });
        // O persister REQUIRES_NEW também atualiza o holder (simula a persistência
        // da falha em transação própria — o estado acumulado alimenta o lockout).
        // SEC-01: simula o comportamento atômico do persister (re-lê o holder
        // FRESCO e re-aplica recordFailedAttempt com maxAttempts/lockoutMinutes).
        org.mockito.Mockito.doAnswer(i -> {
            currentTotp = currentTotp.recordFailedAttempt(
                    i.getArgument(3), i.getArgument(4), i.getArgument(2));
            return null;
        }).when(failurePersister).recordFailure(
                eq(MERCHANT_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
    }

    private String validCode() {
        return Totp.code(SECRET, NOW);
    }

    // ---------- activate (passo 1) ----------

    @Test
    void activateReturnsUriAndSecretAndPersistsEncryptedSecret() {
        when(methodRepository.findActiveByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(methodRepository.findPendingByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        MerchantMfaUseCase.ActivateResult result = service.activate(MERCHANT_ID);

        // Chave manual devolvida 1x = secret base32 embutido na URI do QR
        // (label = nome do lojista, URL-encoded pelo Totp — Spec 94).
        assertThat(result.manualEntryKey()).matches("[A-Z2-7]{32}");
        assertThat(result.otpauthUri()).startsWith("otpauth://totp/FoxCash:")
                .contains("secret=" + result.manualEntryKey());

        // Método PENDING persistido (prova de posse ainda pendente).
        ArgumentCaptor<MerchantMfaMethod> methodCaptor = ArgumentCaptor.forClass(MerchantMfaMethod.class);
        verify(methodRepository).save(methodCaptor.capture());
        assertThat(methodCaptor.getValue().isPending()).isTrue();
        assertThat(methodCaptor.getValue().merchantId()).isEqualTo(MERCHANT_ID);

        // Secret NUNCA persistido em claro: ciphertext v1: (AES-256/GCM).
        ArgumentCaptor<MerchantTotp> totpCaptor = ArgumentCaptor.forClass(MerchantTotp.class);
        verify(totpRepository).save(totpCaptor.capture());
        assertThat(totpCaptor.getValue().secretEncrypted()).startsWith(CryptoConverter.VERSION);
        assertThat(totpCaptor.getValue().secretEncrypted()).isNotEqualTo(result.manualEntryKey());
    }

    @Test
    void activateThrowsMerchantNotFoundForUnknownMerchant() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(MERCHANT_ID))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(methodRepository, never()).save(any());
        verify(totpRepository, never()).save(any());
    }

    @Test
    void activateThrowsIllegalStateWhenMfaAlreadyActive() {
        when(methodRepository.findActiveByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(activeMethod()));

        assertThatThrownBy(() -> service.activate(MERCHANT_ID))
                .isInstanceOf(IllegalStateException.class);

        verify(methodRepository, never()).save(any());
        verify(totpRepository, never()).save(any());
    }

    @Test
    void activateDiscardsPreviousPendingMethod() {
        when(methodRepository.findActiveByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(methodRepository.findPendingByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(pendingMethod()));

        service.activate(MERCHANT_ID);

        verify(methodRepository).deleteByMerchantId(MERCHANT_ID);
    }

    // ---------- confirm (passo 2) ----------

    @Test
    void confirmWithValidCodeActivatesMethodAndGeneratesEightRecoveryCodes() {
        when(methodRepository.findPendingByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(pendingMethod()));
        when(totpRepository.findByMethodId(METHOD_ID)).thenReturn(Optional.of(totp(SECRET)));
        when(recoveryCodeRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        MerchantMfaUseCase.ConfirmResult result = service.confirm(MERCHANT_ID, validCode());

        // Método promovido a ACTIVE.
        ArgumentCaptor<MerchantMfaMethod> methodCaptor = ArgumentCaptor.forClass(MerchantMfaMethod.class);
        verify(methodRepository).save(methodCaptor.capture());
        assertThat(methodCaptor.getValue().isActive()).isTrue();
        assertThat(methodCaptor.getValue().activatedAt()).isEqualTo(NOW);

        // 8 recovery codes one-time (plaintext devolvido 1x).
        assertThat(result.recoveryCodes()).hasSize(8);
        assertThat(result.recoveryCodes()).allMatch(code -> code.startsWith("mrc_"));

        // Auditoria de ativação — actor MERCHANT, merchantId no 6º arg.
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MFA_ACTIVATED), eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), isNull());
    }

    @Test
    void confirmThrowsInvalidMfaWhenNoPendingMethod() {
        when(methodRepository.findPendingByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(MERCHANT_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository, never()).save(any());
    }

    @Test
    void confirmThrowsInvalidMfaAndDiscardsExpiredPendingMethod() {
        MerchantMfaMethod expired = MerchantMfaMethod.pending(METHOD_ID, MERCHANT_ID, NOW.minus(Duration.ofMinutes(20)),
                NOW.minus(Duration.ofMinutes(10)));
        when(methodRepository.findPendingByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.confirm(MERCHANT_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository).deleteByMerchantId(MERCHANT_ID);
        verify(methodRepository, never()).save(any());
    }

    @Test
    void confirmWithWrongCodeThrowsInvalidMfaAndKeepsMethodPending() {
        when(methodRepository.findPendingByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(pendingMethod()));
        when(totpRepository.findByMethodId(METHOD_ID)).thenReturn(Optional.of(totp(SECRET)));

        assertThatThrownBy(() -> service.confirm(MERCHANT_ID, "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository, never()).save(any());
        verify(recoveryCodeRepository, never()).saveAll(anyList());
    }

    // ---------- deactivate ----------

    @Test
    void deactivateRequiresCorrectPasswordAndValidCode() {
        stubActiveMfa(SECRET);
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);

        service.deactivate(MERCHANT_ID, validCode(), "senha-certa");

        verify(methodRepository).deleteByMerchantId(MERCHANT_ID);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MFA_DEACTIVATED), eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), isNull());
    }

    @Test
    void deactivateWithWrongPasswordThrowsInvalidMfaAndKeepsMfa() {
        stubActiveMfa(SECRET);
        when(passwordHasher.matches("senha-errada", PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.deactivate(MERCHANT_ID, validCode(), "senha-errada"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository, never()).deleteByMerchantId(MERCHANT_ID);
    }

    @Test
    void deactivateWithoutCredentialThrowsInvalidMfaUniform() {
        // SEC-04 espelho: credencial ausente também é 401 uniforme (sem oráculo
        // sobre a existência do registro de credencial).
        stubActiveMfa(SECRET);
        when(credentialRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(MERCHANT_ID, validCode(), "senha-certa"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository, never()).deleteByMerchantId(MERCHANT_ID);
    }

    @Test
    void deactivateWithRecoveryCodeAndPasswordSucceeds() {
        stubActiveMfa(SECRET);
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);
        stubRecoveryCode();

        service.deactivate(MERCHANT_ID, "mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "senha-certa");

        verify(methodRepository).deleteByMerchantId(MERCHANT_ID);
    }

    @Test
    void deactivateThrowsMerchantNotFoundForUnknownMerchant() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(MERCHANT_ID, validCode(), "senha-certa"))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    // ---------- regenerateRecoveryCodes ----------

    @Test
    void regenerateRecoveryCodesReplacesAllCodes() {
        stubActiveMfa(SECRET);
        when(recoveryCodeRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        List<String> codes = service.regenerateRecoveryCodes(MERCHANT_ID, validCode());

        assertThat(codes).hasSize(8);
        verify(recoveryCodeRepository).deleteByMethodId(METHOD_ID);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MFA_RECOVERY_REGENERATED), eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), isNull());
    }

    @Test
    void regenerateRecoveryCodesThrowsInvalidMfaWhenMfaInactive() {
        when(methodRepository.findActiveByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regenerateRecoveryCodes(MERCHANT_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(recoveryCodeRepository, never()).deleteByMethodId(any());
    }

    // ---------- hasActiveMfa ----------

    @Test
    void hasActiveMfaReflectsActiveMethodPresence() {
        when(methodRepository.findActiveByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(activeMethod()));
        assertThat(service.hasActiveMfa(MERCHANT_ID)).isTrue();

        when(methodRepository.findActiveByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        assertThat(service.hasActiveMfa(MERCHANT_ID)).isFalse();
    }

    // ---------- verifySecondFactor (login 2FA) ----------

    @Test
    void verifySecondFactorWithNullCodeThrowsUniform401() {
        // Auditoria E2E do master (2026-08-06): login sem código com MFA ativo
        // caía em 409 (NPE no DocumentHasher.hash(null)). Espelhado: 401 uniforme.
        stubActiveMfa(SECRET);

        assertThatThrownBy(() -> service.verifySecondFactor(MERCHANT_ID, null))
                .isInstanceOf(InvalidMfaCodeException.class);

        // A falha é persistida pelo persister REQUIRES_NEW (o throw reverteria o
        // save/evento comuns na transação do login).
        verify(failurePersister).recordFailure(eq(MERCHANT_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
        assertThat(currentTotp.failedAttempts()).isEqualTo(1);
    }

    @Test
    void verifySecondFactorAcceptsValidTotpCode() {
        stubActiveMfa(SECRET);

        service.verifySecondFactor(MERCHANT_ID, validCode());

        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MFA_LOGIN_SUCCESS), eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), isNull());
        // Anti-replay: o hash do código usado é persistido.
        assertThat(currentTotp.lastCodeHash()).isEqualTo(DocumentHasher.hash(validCode()));
        assertThat(currentTotp.failedAttempts()).isZero();
    }

    @Test
    void verifySecondFactorRejectsWrongCodeWithUniform401() {
        stubActiveMfa(SECRET);

        assertThatThrownBy(() -> service.verifySecondFactor(MERCHANT_ID, "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(failurePersister).recordFailure(eq(MERCHANT_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
        assertThat(currentTotp.failedAttempts()).isEqualTo(1);
    }

    @Test
    void verifySecondFactorRejectsReplayOfLastUsedCode() {
        stubActiveMfa(SECRET);
        // last_code_hash = hash do código atual → replay detectado antes do verify.
        currentTotp = MerchantTotp.restore(METHOD_ID,
                cryptoConverter.convertToDatabaseColumn(SECRET),
                DocumentHasher.hash(validCode()), 0, null);

        assertThatThrownBy(() -> service.verifySecondFactor(MERCHANT_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(failurePersister).recordFailure(eq(MERCHANT_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
    }

    @Test
    void verifySecondFactorLocksAfterMaxFailuresAndExpiresByItself() {
        stubActiveMfa(SECRET);

        // 5 falhas seguidas → lockout disparado na 5ª (MFA_LOCKED).
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verifySecondFactor(MERCHANT_ID, "000000"))
                    .isInstanceOf(InvalidMfaCodeException.class);
        }
        assertThat(currentTotp.failedAttempts()).isEqualTo(5);
        assertThat(currentTotp.isLocked(NOW)).isTrue();
        verify(failurePersister, org.mockito.Mockito.times(5))
                .recordFailure(eq(MERCHANT_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));

        // Mesmo código correto é recusado durante a janela (401 uniforme, sem oráculo).
        assertThatThrownBy(() -> service.verifySecondFactor(MERCHANT_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        // Lockout expira sozinho → código correto passa após a janela.
        currentTotp = MerchantTotp.restore(METHOD_ID,
                cryptoConverter.convertToDatabaseColumn(SECRET), null, 5, NOW.minusSeconds(1));
        service.verifySecondFactor(MERCHANT_ID, validCode());
        verify(activityRecorder, org.mockito.Mockito.atLeastOnce()).record(eq(PlatformActorType.MERCHANT),
                eq(MERCHANT_ID), eq(PlatformAction.MFA_LOGIN_SUCCESS), eq("MERCHANT"), eq(MERCHANT_ID),
                eq(MERCHANT_ID), isNull());
    }

    @Test
    void verifySecondFactorAcceptsRecoveryCodeOnce() {
        stubActiveMfa(SECRET);
        stubRecoveryCode();

        service.verifySecondFactor(MERCHANT_ID, "mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        // SEC-20: o consumo é ATÔMICO (UPDATE condicional) — nunca leitura+save.
        verify(recoveryCodeRepository).consumeIfOpen(eq(METHOD_ID),
                eq(DocumentHasher.hash("mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")), eq(NOW));
        verify(recoveryCodeRepository, never()).saveAll(anyList());
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MFA_RECOVERY_USED), eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), isNull());
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MFA_LOGIN_SUCCESS), eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), isNull());
    }

    @Test
    void verifySecondFactorRecoveryCodeAlreadyUsedFallsThroughToTotp() {
        // SEC-20: código já consumido (consumeIfOpen=false — a corrida perdeu ou
        // é replay) NÃO autentica: cai no caminho do TOTP e a falha conta (401
        // uniforme, mesmo sem oráculo).
        stubActiveMfa(SECRET);
        when(recoveryCodeRepository.consumeIfOpen(METHOD_ID,
                DocumentHasher.hash("mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), NOW))
                .thenReturn(false);

        assertThatThrownBy(() -> service.verifySecondFactor(
                MERCHANT_ID, "mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(failurePersister).recordFailure(eq(MERCHANT_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
        verify(recoveryCodeRepository, never()).saveAll(anyList());
    }

    @Test
    void verifySecondFactorThrowsInvalidMfaWhenNoActiveMethod() {
        when(methodRepository.findActiveByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifySecondFactor(MERCHANT_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    void verifySecondFactorThrowsInvalidMfaWhenLockedEvenWithCorrectCode() {
        stubActiveMfa(SECRET);
        currentTotp = MerchantTotp.restore(METHOD_ID,
                cryptoConverter.convertToDatabaseColumn(SECRET), null, 5, NOW.plusSeconds(60));

        assertThatThrownBy(() -> service.verifySecondFactor(MERCHANT_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        // Nenhuma auditoria de sucesso nem tentativa de verificar o código.
        verify(activityRecorder, never()).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MFA_LOGIN_SUCCESS), eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), isNull());
    }

    // ---------- mutations (bypass do 2º fator / flags) ----------

    @Test
    void deactivateWithInvalidCodeThrowsEvenWithCorrectPassword() {
        // Mutation (VoidMethodCall): verifySecondFactor removido da desativação
        // → o 2º fator deixaria de ser exigido (bypass do MFA) com senha correta.
        stubActiveMfa(SECRET);
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.deactivate(MERCHANT_ID, "000000", "senha-certa"))
                .isInstanceOf(InvalidMfaCodeException.class);
        verify(methodRepository, never()).deleteByMerchantId(MERCHANT_ID);
    }

    @Test
    void regenerateRecoveryCodesWithInvalidCodeThrows() {
        // Mutation (VoidMethodCall): verifySecondFactor removido da regeneração
        // → aceitaria qualquer código (bypass do MFA).
        stubActiveMfa(SECRET);

        assertThatThrownBy(() -> service.regenerateRecoveryCodes(MERCHANT_ID, "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);
        verify(recoveryCodeRepository, never()).deleteByMethodId(any());
    }

    @Test
    void methodStateFlagsReflectStatus() {
        MerchantMfaMethod active = activeMethod();
        MerchantMfaMethod pending = pendingMethod();
        assertThat(active.isPending()).isFalse();
        assertThat(active.isActive()).isTrue();
        assertThat(pending.isPending()).isTrue();
        assertThat(pending.isActive()).isFalse();
        assertThat(active.type()).isEqualTo("TOTP");
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.createdAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(pending.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    // ---------- helpers ----------

    /** Stub de um recovery code não usado para o método ativo (SEC-20: consume atômico). */
    private void stubRecoveryCode() {
        String code = "mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        when(recoveryCodeRepository.consumeIfOpen(METHOD_ID, DocumentHasher.hash(code), NOW))
                .thenReturn(true);
    }
}
