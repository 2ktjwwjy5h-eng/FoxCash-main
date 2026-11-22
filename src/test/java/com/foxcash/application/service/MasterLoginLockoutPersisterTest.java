package com.foxcash.application.service;

import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.port.out.MasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterLoginLockoutPersister} (auditoria B-08 + SEC-01).
 *
 * <p>Desde a revisão de segurança 2026-08-11 (SEC-01) o incremento do contador
 * de falhas é <b>atômico</b>: o persister re-lê a linha com
 * {@code findByIdForUpdate} (lock pessimista) e re-aplica
 * {@link Master#registerFailedLogin} sobre o estado FRESCO — sem lost update
 * entre tentativas paralelas (o que burlaria o lockout). Se outra tentativa
 * concorrente já disparou o lockout, a falha não é contada.
 */
class MasterLoginLockoutPersisterTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final MasterRepository repository = mock(MasterRepository.class);
    private MasterLoginLockoutPersister persister;

    @BeforeEach
    void setUp() {
        persister = new MasterLoginLockoutPersister(
                mock(PlatformTransactionManager.class), repository);
    }

    @Test
    void recordReReadsWithForUpdateAndPersistsFreshState() {
        // Estado no banco: 4 falhas (não-lockado). A falha corrente → 5 → lockout.
        when(repository.findByIdForUpdate(MASTER_ID))
                .thenReturn(Optional.of(Master.restore(MASTER_ID, "master", "hash", "M",
                        true, false, NOW.minus(Duration.ofDays(1)), 4, null)));

        persister.record(MASTER_ID, NOW, 5, LOCKOUT);

        ArgumentCaptor<Master> captor = ArgumentCaptor.forClass(Master.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().failedLoginAttempts()).isZero(); // resetado no lockout
        assertThat(captor.getValue().isLocked(NOW)).isTrue();
        assertThat(captor.getValue().lockedUntil()).isEqualTo(NOW.plus(LOCKOUT));
    }

    @Test
    void recordSkipsWhenAlreadyLocked() {
        // SEC-01: outra tentativa concorrente já disparou o lockout → não conta
        // (a janela não é estendida por tentativas que caíram dentro dela).
        when(repository.findByIdForUpdate(MASTER_ID))
                .thenReturn(Optional.of(Master.restore(MASTER_ID, "master", "hash", "M",
                        true, false, NOW.minus(Duration.ofDays(1)), 5, NOW.plusSeconds(600))));

        persister.record(MASTER_ID, NOW, 5, LOCKOUT);

        verify(repository, never()).save(any(Master.class));
    }
}
