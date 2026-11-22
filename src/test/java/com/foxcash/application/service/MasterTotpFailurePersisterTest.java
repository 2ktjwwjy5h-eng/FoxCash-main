package com.foxcash.application.service;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.master.MasterTotp;
import com.foxcash.domain.port.out.MasterTotpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterTotpFailurePersister} (Spec 44, auditoria 2026-08-06).
 *
 * <p>O persister grava a falha de TOTP ({@code failed_attempts}/{@code locked_until})
 * e o evento {@code MFA_LOGIN_FAILED}/{@code MFA_LOCKED} em transação PRÓPRIA
 * (REQUIRES_NEW) — sobrevive ao rollback da ação (login). Desde a revisão de
 * segurança 2026-08-11 (SEC-01) o incremento é <b>atômico</b>: o persister
 * re-lê a linha com {@code findByIdForUpdate} (lock pessimista) e re-aplica
 * {@link MasterTotp#recordFailedAttempt} sobre o estado FRESCO — sem lost
 * update entre verificações concorrentes.
 */
class MasterTotpFailurePersisterTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID METHOD_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private final MasterTotpRepository totpRepository = mock(MasterTotpRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private MasterTotpFailurePersister persister;

    @BeforeEach
    void setUp() {
        // TransactionTemplate executa getTransaction → callback → commit; com o
        // manager mockado, o callback roda (status null é ignorado pelo corpo).
        persister = new MasterTotpFailurePersister(
                mock(PlatformTransactionManager.class), totpRepository, activityRecorder);
    }

    @Test
    void recordFailurePersistsTotpAndLogsMfaLoginFailed() {
        when(totpRepository.findByIdForUpdate(METHOD_ID))
                .thenReturn(Optional.of(MasterTotp.restore(METHOD_ID, "v1:cipher", null, 0, null)));

        persister.recordFailure(MASTER_ID, METHOD_ID, NOW, 5, 15);

        ArgumentCaptor<MasterTotp> captor = ArgumentCaptor.forClass(MasterTotp.class);
        verify(totpRepository).save(captor.capture());
        // Estado persistido é o FRESCO re-incrementado (1 falha, sem lockout).
        assertThat(captor.getValue().failedAttempts()).isEqualTo(1);
        assertThat(captor.getValue().isLocked(NOW)).isFalse();
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_LOGIN_FAILED), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
    }

    @Test
    void recordFailureWhenLockedLogsMfaLockedInstead() {
        // 4 falhas acumuladas → a 5ª dispara o lockout (evento MFA_LOCKED).
        when(totpRepository.findByIdForUpdate(METHOD_ID))
                .thenReturn(Optional.of(MasterTotp.restore(METHOD_ID, "v1:cipher", null, 4, null)));

        persister.recordFailure(MASTER_ID, METHOD_ID, NOW, 5, 15);

        ArgumentCaptor<MasterTotp> captor = ArgumentCaptor.forClass(MasterTotp.class);
        verify(totpRepository).save(captor.capture());
        assertThat(captor.getValue().failedAttempts()).isEqualTo(5);
        assertThat(captor.getValue().isLocked(NOW)).isTrue();
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MFA_LOCKED), eq("MASTER"), eq(MASTER_ID), isNull(), isNull());
    }

    @Test
    void recordFailureWhenAlreadyLockedSkipsIncrement() {
        // SEC-01: lockout já disparado por outra verificação concorrente → a
        // tentativa não é contada (a janela não é estendida).
        when(totpRepository.findByIdForUpdate(METHOD_ID))
                .thenReturn(Optional.of(MasterTotp.restore(METHOD_ID, "v1:cipher", null, 5, NOW.plusSeconds(60))));

        persister.recordFailure(MASTER_ID, METHOD_ID, NOW, 5, 15);

        verify(totpRepository, never()).save(any(MasterTotp.class));
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordFailureCommitsInsideItsOwnTransaction() {
        // ResourcelessTransactionManager: a transação REQUIRES_NEW é iniciada e
        // commitada — se algo lançasse, o save/atividade não aconteceriam.
        when(totpRepository.findByIdForUpdate(METHOD_ID))
                .thenReturn(Optional.of(MasterTotp.restore(METHOD_ID, "v1:cipher", null, 2, null)));
        when(totpRepository.save(any(MasterTotp.class))).thenAnswer(i -> i.getArgument(0));

        persister.recordFailure(MASTER_ID, METHOD_ID, NOW, 5, 15);

        // A chamada completou sem exceção → transação própria commitada; o save
        // recebeu o estado re-incrementado (3 falhas).
        ArgumentCaptor<MasterTotp> captor = ArgumentCaptor.forClass(MasterTotp.class);
        verify(totpRepository).save(captor.capture());
        assertThat(captor.getValue().failedAttempts()).isEqualTo(3);
    }
}
