package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.exception.MasterNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterMfaMethod;
import com.foxcash.domain.model.master.MasterTotp;
import com.foxcash.domain.port.in.MasterMfaUseCase;
import com.foxcash.domain.port.out.MasterMfaMethodRepository;
import com.foxcash.domain.port.out.MasterRecoveryCodeRepository;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.MasterTotpRepository;
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
 * Testes do {@link MasterMfaService} (Spec 44): ativação em 2 passos
 * (activate → confirm com prova de posse), recovery codes single-use,
 * desativação com senha + 2º fator, regeneração e verificação do 2º fator
 * (TOTP ±1 passo, anti-replay e lockout que expira sozinho — 401 uniforme).
 */
class MasterMfaServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID METHOD_ID = UUID.randomUUID();
    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuv";
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private final MasterRepository masterRepository = mock(MasterRepository.class);
    private final MasterMfaMethodRepository methodRepository = mock(MasterMfaMethodRepository.class);
    private final MasterTotpRepository totpRepository = mock(MasterTotpRepository.class);
    private final MasterRecoveryCodeRepository recoveryCodeRepository = mock(MasterRecoveryCodeRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final CryptoConverter cryptoConverter =
            new CryptoConverter(properties("chave-de-teste-segura-32bytes!"));
    private final MasterTotpFailurePersister failurePersister = mock(MasterTotpFailurePersister.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);

    private MasterMfaService service;
    private MasterTotp currentTotp;

    @BeforeEach
    void setUp() {
        service = new MasterMfaService(
                masterRepository, methodRepository, totpRepository, recoveryCodeRepository,
                passwordHasher, cryptoConverter, failurePersister, activityRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.of(activeMaster()));
    }

    private static CryptoProperties properties(String secret) {
        CryptoProperties props = new CryptoProperties();
        props.setSecret(secret);
        return props;
    }

    private Master activeMaster() {
        return Master.restore(MASTER_ID, "master", PASSWORD_HASH, "Master Principal", true, NOW);
    }

    private MasterMfaMethod pendingMethod() {
        return MasterMfaMethod.pending(METHOD_ID, MASTER_ID, NOW, NOW.plus(Duration.ofMinutes(10)));
    }

    private MasterMfaMethod activeMethod() {
        return MasterMfaMethod.restore(METHOD_ID, MASTER_ID, "TOTP", "ACTIVE",
                NOW.minusSeconds(60), NOW.minusSeconds(30), null);
    }

    private MasterTotp totp(String secret) {
        return MasterTotp.restore(METHOD_ID, cryptoConverter.convertToDatabaseColumn(secret),
                null, 0, null);
    }

    /** Método ativo + TOTP com estado mutável (persistência simulada via holder). */
    private void stubActiveMfa(String secret) {
        currentTotp = totp(secret);
        when(methodRepository.findActiveByMasterId(MASTER_ID)).thenReturn(Optional.of(activeMethod()));
        when(totpRepository.findByMethodId(METHOD_ID)).thenAnswer(i -> Optional.of(currentTotp));
        when(totpRepository.save(any(MasterTotp.class))).thenAnswer(i -> {
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
                eq(MASTER_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
    }

    private String validCode() {
        return Totp.code(SECRET, NOW);
    }

    // ---------- activate (passo 1) ----------

    @Test
    void activateReturnsUriAndSecretAndPersistsEncryptedSecret() {
        when(methodRepository.findActiveByMasterId(MASTER_ID)).thenReturn(Optional.empty());
        when(methodRepository.findPendingByMasterId(MASTER_ID)).thenReturn(Optional.empty());

        MasterMfaUseCase.ActivateResult result = service.activate(MASTER_ID);

        // Chave manual devolvida 1x = secret base32 embutido na URI do QR.
        assertThat(result.manualEntryKey()).matches("[A-Z2-7]{32}");
        assertThat(result.otpauthUri()).startsWith("otpauth://totp/FoxCash:")
                .contains("secret=" + result.manualEntryKey());

        // Método PENDING persistido (prova de posse ainda pendente).
        ArgumentCaptor<MasterMfaMethod> methodCaptor = ArgumentCaptor.forClass(MasterMfaMethod.class);
        verify(methodRepository).save(methodCaptor.capture());
        assertThat(methodCaptor.getValue().isPending()).isTrue();
        assertThat(methodCaptor.getValue().masterId()).isEqualTo(MASTER_ID);

        // Secret NUNCA persistido em claro: ciphertext v1: (AES-256/GCM).
        ArgumentCaptor<MasterTotp> totpCaptor = ArgumentCaptor.forClass(MasterTotp.class);
        verify(totpRepository).save(totpCaptor.capture());
        assertThat(totpCaptor.getValue().secretEncrypted()).startsWith(CryptoConverter.VERSION);
        assertThat(totpCaptor.getValue().secretEncrypted()).isNotEqualTo(result.manualEntryKey());
    }

    @Test
    void activateThrowsMasterNotFoundForUnknownMaster() {
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(MASTER_ID))
                .isInstanceOf(MasterNotFoundException.class);

        verify(methodRepository, never()).save(any());
        verify(totpRepository, never()).save(any());
    }

    @Test
    void activateThrowsIllegalStateWhenMfaAlreadyActive() {
        when(methodRepository.findActiveByMasterId(MASTER_ID)).thenReturn(Optional.of(activeMethod()));

        assertThatThrownBy(() -> service.activate(MASTER_ID))
                .isInstanceOf(IllegalStateException.class);

        verify(methodRepository, never()).save(any());
        verify(totpRepository, never()).save(any());
    }

    @Test
    void activateDiscardsPreviousPendingMethod() {
        when(methodRepository.findActiveByMasterId(MASTER_ID)).thenReturn(Optional.empty());
        when(methodRepository.findPendingByMasterId(MASTER_ID)).thenReturn(Optional.of(pendingMethod()));

        service.activate(MASTER_ID);

        verify(methodRepository).deleteByMasterId(MASTER_ID);
    }

    // ---------- confirm (passo 2) ----------

    @Test
    void confirmWithValidCodeActivatesMethodAndGeneratesEightRecoveryCodes() {
        when(methodRepository.findPendingByMasterId(MASTER_ID)).thenReturn(Optional.of(pendingMethod()));
        when(totpRepository.findByMethodId(METHOD_ID)).thenReturn(Optional.of(totp(SECRET)));
        when(recoveryCodeRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        MasterMfaUseCase.ConfirmResult result = service.confirm(MASTER_ID, validCode());

        // Método promovido a ACTIVE.
        ArgumentCaptor<MasterMfaMethod> methodCaptor = ArgumentCaptor.forClass(MasterMfaMethod.class);
        verify(methodRepository).save(methodCaptor.capture());
        assertThat(methodCaptor.getValue().isActive()).isTrue();
        assertThat(methodCaptor.getValue().activatedAt()).isEqualTo(NOW);

        // 8 recovery codes one-time (plaintext devolvido 1x).
        assertThat(result.recoveryCodes()).hasSize(8);
        assertThat(result.recoveryCodes()).allMatch(code -> code.startsWith("mrc_"));

        // Auditoria de ativação.
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_ACTIVATED), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
    }

    @Test
    void confirmThrowsInvalidMfaWhenNoPendingMethod() {
        when(methodRepository.findPendingByMasterId(MASTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(MASTER_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository, never()).save(any());
    }

    @Test
    void confirmThrowsInvalidMfaAndDiscardsExpiredPendingMethod() {
        MasterMfaMethod expired = MasterMfaMethod.pending(METHOD_ID, MASTER_ID, NOW.minus(Duration.ofMinutes(20)),
                NOW.minus(Duration.ofMinutes(10)));
        when(methodRepository.findPendingByMasterId(MASTER_ID)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.confirm(MASTER_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository).deleteByMasterId(MASTER_ID);
        verify(methodRepository, never()).save(any());
    }

    @Test
    void confirmWithWrongCodeThrowsInvalidMfaAndKeepsMethodPending() {
        when(methodRepository.findPendingByMasterId(MASTER_ID)).thenReturn(Optional.of(pendingMethod()));
        when(totpRepository.findByMethodId(METHOD_ID)).thenReturn(Optional.of(totp(SECRET)));

        assertThatThrownBy(() -> service.confirm(MASTER_ID, "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository, never()).save(any());
        verify(recoveryCodeRepository, never()).saveAll(anyList());
    }

    // ---------- deactivate ----------

    @Test
    void deactivateRequiresCorrectPasswordAndValidCode() {
        stubActiveMfa(SECRET);
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);

        service.deactivate(MASTER_ID, validCode(), "senha-certa");

        verify(methodRepository).deleteByMasterId(MASTER_ID);
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_DEACTIVATED), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
    }

    @Test
    void deactivateWithWrongPasswordThrowsInvalidMfaAndKeepsMfa() {
        stubActiveMfa(SECRET);
        when(passwordHasher.matches("senha-errada", PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.deactivate(MASTER_ID, validCode(), "senha-errada"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(methodRepository, never()).deleteByMasterId(MASTER_ID);
    }

    @Test
    void deactivateWithRecoveryCodeAndPasswordSucceeds() {
        stubActiveMfa(SECRET);
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);
        stubRecoveryCode();

        service.deactivate(MASTER_ID, "mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "senha-certa");

        verify(methodRepository).deleteByMasterId(MASTER_ID);
    }

    @Test
    void deactivateThrowsMasterNotFoundForUnknownMaster() {
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(MASTER_ID, validCode(), "senha-certa"))
                .isInstanceOf(MasterNotFoundException.class);
    }

    // ---------- regenerateRecoveryCodes ----------

    @Test
    void regenerateRecoveryCodesReplacesAllCodes() {
        stubActiveMfa(SECRET);
        when(recoveryCodeRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        List<String> codes = service.regenerateRecoveryCodes(MASTER_ID, validCode());

        assertThat(codes).hasSize(8);
        verify(recoveryCodeRepository).deleteByMethodId(METHOD_ID);
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_RECOVERY_REGENERATED), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
    }

    @Test
    void regenerateRecoveryCodesThrowsInvalidMfaWhenMfaInactive() {
        when(methodRepository.findActiveByMasterId(MASTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regenerateRecoveryCodes(MASTER_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(recoveryCodeRepository, never()).deleteByMethodId(any());
    }

    // ---------- hasActiveMfa ----------

    @Test
    void hasActiveMfaReflectsActiveMethodPresence() {
        when(methodRepository.findActiveByMasterId(MASTER_ID)).thenReturn(Optional.of(activeMethod()));
        assertThat(service.hasActiveMfa(MASTER_ID)).isTrue();

        when(methodRepository.findActiveByMasterId(MASTER_ID)).thenReturn(Optional.empty());
        assertThat(service.hasActiveMfa(MASTER_ID)).isFalse();
    }

    // ---------- verifySecondFactor (login 2FA) ----------

    @Test
    void verifySecondFactorWithNullCodeThrowsUniform401() {
        // Auditoria E2E 2026-08-06: login sem código com MFA ativo caía em 409
        // (NPE no DocumentHasher.hash(null) → IllegalStateException). Deve ser
        // 401 uniforme como qualquer código inválido — sem oráculo.
        stubActiveMfa(SECRET);

        assertThatThrownBy(() -> service.verifySecondFactor(MASTER_ID, null))
                .isInstanceOf(InvalidMfaCodeException.class);

        // Auditoria 2026-08-06: a falha é persistida pelo persister REQUIRES_NEW
        // (o throw reverteria o save/evento comuns na transação do login).
        verify(failurePersister).recordFailure(eq(MASTER_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
        assertThat(currentTotp.failedAttempts()).isEqualTo(1);
    }

    @Test
    void verifySecondFactorAcceptsValidTotpCode() {
        stubActiveMfa(SECRET);

        service.verifySecondFactor(MASTER_ID, validCode());

        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_LOGIN_SUCCESS), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
        // Anti-replay: o hash do código usado é persistido.
        assertThat(currentTotp.lastCodeHash()).isEqualTo(DocumentHasher.hash(validCode()));
        assertThat(currentTotp.failedAttempts()).isZero();
    }

    @Test
    void verifySecondFactorRejectsWrongCodeWithUniform401() {
        stubActiveMfa(SECRET);

        assertThatThrownBy(() -> service.verifySecondFactor(MASTER_ID, "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(failurePersister).recordFailure(eq(MASTER_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
        assertThat(currentTotp.failedAttempts()).isEqualTo(1);
    }

    @Test
    void verifySecondFactorRejectsReplayOfLastUsedCode() {
        stubActiveMfa(SECRET);
        // last_code_hash = hash do código atual → replay detectado antes do verify.
        currentTotp = MasterTotp.restore(METHOD_ID,
                cryptoConverter.convertToDatabaseColumn(SECRET),
                DocumentHasher.hash(validCode()), 0, null);

        assertThatThrownBy(() -> service.verifySecondFactor(MASTER_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(failurePersister).recordFailure(eq(MASTER_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
    }

    @Test
    void verifySecondFactorLocksAfterMaxFailuresAndExpiresByItself() {
        stubActiveMfa(SECRET);

        // 5 falhas seguidas → lockout disparado na 5ª (MFA_LOCKED).
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verifySecondFactor(MASTER_ID, "000000"))
                    .isInstanceOf(InvalidMfaCodeException.class);
        }
        assertThat(currentTotp.failedAttempts()).isEqualTo(5);
        assertThat(currentTotp.isLocked(NOW)).isTrue();
        // A 5ª falha dispara o lockout — persistido pelo persister (MFA_LOCKED).
        // SEC-01: o evento MFA_LOCKED vs MFA_LOGIN_FAILED é decidido internamente
        // pelo persister (agora atômico); o argumento `locked` deixou de existir.
        verify(failurePersister, org.mockito.Mockito.times(5))
                .recordFailure(eq(MASTER_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));

        // Mesmo código correto é recusado durante a janela (401 uniforme, sem oráculo).
        assertThatThrownBy(() -> service.verifySecondFactor(MASTER_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        // Lockout expira sozinho → código correto passa após a janela.
        currentTotp = MasterTotp.restore(METHOD_ID,
                cryptoConverter.convertToDatabaseColumn(SECRET), null, 5, NOW.minusSeconds(1));
        service.verifySecondFactor(MASTER_ID, validCode());
        verify(activityRecorder, org.mockito.Mockito.atLeastOnce()).record(eq(PlatformActorType.MASTER),
                eq(MASTER_ID), eq(PlatformAction.MFA_LOGIN_SUCCESS), eq("MASTER"), eq(MASTER_ID),
                isNull(), isNull());
    }

    @Test
    void verifySecondFactorAcceptsRecoveryCodeOnce() {
        stubActiveMfa(SECRET);
        stubRecoveryCode();

        service.verifySecondFactor(MASTER_ID, "mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        // SEC-20: o consumo é ATÔMICO (UPDATE condicional) — nunca leitura+save.
        verify(recoveryCodeRepository).consumeIfOpen(eq(METHOD_ID),
                eq(DocumentHasher.hash("mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")), eq(NOW));
        verify(recoveryCodeRepository, never()).saveAll(anyList());
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_RECOVERY_USED), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_LOGIN_SUCCESS), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
    }

    @Test
    void verifySecondFactorRecoveryCodeAlreadyUsedFallsThroughToTotp() {
        // SEC-20: código já consumido (consumeIfOpen=false — a corrida perdeu ou
        // é replay) NÃO autentica: cai no caminho do TOTP e a falha conta (401
        // uniforme, mesmo sem oráculo). Antes, a leitura+save deixava dois
        // logins concorrentes passarem com o MESMO código.
        stubActiveMfa(SECRET);
        when(recoveryCodeRepository.consumeIfOpen(METHOD_ID,
                DocumentHasher.hash("mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), NOW))
                .thenReturn(false);

        assertThatThrownBy(() -> service.verifySecondFactor(
                MASTER_ID, "mrc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(failurePersister).recordFailure(eq(MASTER_ID), eq(METHOD_ID), eq(NOW), eq(5), eq(15L));
        verify(recoveryCodeRepository, never()).saveAll(anyList());
    }

    @Test
    void verifySecondFactorThrowsInvalidMfaWhenNoActiveMethod() {
        when(methodRepository.findActiveByMasterId(MASTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifySecondFactor(MASTER_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    void verifySecondFactorThrowsInvalidMfaWhenLockedEvenWithCorrectCode() {
        stubActiveMfa(SECRET);
        currentTotp = MasterTotp.restore(METHOD_ID,
                cryptoConverter.convertToDatabaseColumn(SECRET), null, 5, NOW.plusSeconds(60));

        assertThatThrownBy(() -> service.verifySecondFactor(MASTER_ID, validCode()))
                .isInstanceOf(InvalidMfaCodeException.class);

        // Nenhuma auditoria de sucesso nem tentativa de verificar o código.
        verify(activityRecorder, never()).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_LOGIN_SUCCESS), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
    }

    // ---------- round 11 (mutations: L145/L155 bypass do 2º fator; L49/L53 flags) ----------

    @Test
    void deactivateWithInvalidCodeThrowsEvenWithCorrectPassword() {
        // Mutation (VoidMethodCall L145): verifySecondFactor removido da desativação
        // → o 2º fator deixaria de ser exigido (bypass do MFA) com senha correta.
        stubActiveMfa(SECRET);
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.deactivate(MASTER_ID, "000000", "senha-certa"))
                .isInstanceOf(InvalidMfaCodeException.class);
        verify(methodRepository, never()).deleteByMasterId(MASTER_ID);
    }

    @Test
    void regenerateRecoveryCodesWithInvalidCodeThrows() {
        // Mutation (VoidMethodCall L155): verifySecondFactor removido da regeneração
        // → aceitaria qualquer código (bypass do MFA).
        stubActiveMfa(SECRET);

        assertThatThrownBy(() -> service.regenerateRecoveryCodes(MASTER_ID, "000000"))
                .isInstanceOf(InvalidMfaCodeException.class);
        verify(recoveryCodeRepository, never()).deleteByMethodId(any());
    }

    @Test
    void methodStateFlagsReflectStatus() {
        // Mutations (BooleanTrueReturnVals L49/L53): isPending/isActive refletem o
        // status real — um método ACTIVE não é PENDING e um PENDING não é ACTIVE.
        MasterMfaMethod active = activeMethod();
        MasterMfaMethod pending = pendingMethod();
        assertThat(active.isPending()).isFalse();
        assertThat(active.isActive()).isTrue();
        assertThat(pending.isPending()).isTrue();
        assertThat(pending.isActive()).isFalse();
        // Round 11 (getters do método): EmptyObjectReturnVals L70/L74
        // (type/status) e NullReturnVals L78/L86 (createdAt/expiresAt) expostos.
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
