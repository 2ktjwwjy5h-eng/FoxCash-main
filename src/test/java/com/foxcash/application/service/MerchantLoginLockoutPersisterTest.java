package com.foxcash.application.service;

import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.domain.port.out.MerchantCredentialRepository;
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
 * Testes do {@link MerchantLoginLockoutPersister} (auditoria B-06 + SEC-01).
 *
 * <p>Desde a revisão de segurança 2026-08-11 (SEC-01) o incremento do contador
 * de falhas é <b>atômico</b>: o persister re-lê a linha com
 * {@code findByMerchantIdForUpdate} (lock pessimista) e re-aplica
 * {@link MerchantCredential#registerFailedLogin} sobre o estado FRESCO — sem
 * lost update entre tentativas paralelas (o que burlaria o lockout). Se outra
 * tentativa concorrente já disparou o lockout, a falha não é contada.
 */
class MerchantLoginLockoutPersisterTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final MerchantCredentialRepository repository = mock(MerchantCredentialRepository.class);
    private MerchantLoginLockoutPersister persister;

    @BeforeEach
    void setUp() {
        persister = new MerchantLoginLockoutPersister(
                mock(PlatformTransactionManager.class), repository);
    }

    @Test
    void recordReReadsWithForUpdateAndPersistsFreshState() {
        // Estado no banco: 4 falhas (não-lockado). A falha corrente → 5 → lockout.
        when(repository.findByMerchantIdForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(MerchantCredential.restore(
                        CREDENTIAL_ID, MERCHANT_ID, "hash",
                        NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)),
                        null, 4, null)));

        persister.record(MERCHANT_ID, NOW, 5, LOCKOUT);

        ArgumentCaptor<MerchantCredential> captor = ArgumentCaptor.forClass(MerchantCredential.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().failedLoginAttempts()).isZero(); // resetado no lockout
        assertThat(captor.getValue().isLocked(NOW)).isTrue();
        assertThat(captor.getValue().lockedUntil()).isEqualTo(NOW.plus(LOCKOUT));
    }

    @Test
    void recordSkipsWhenAlreadyLocked() {
        // SEC-01: outra tentativa concorrente já disparou o lockout → não conta
        // (a janela não é estendida por tentativas que caíram dentro dela).
        when(repository.findByMerchantIdForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(MerchantCredential.restore(
                        CREDENTIAL_ID, MERCHANT_ID, "hash",
                        NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)),
                        null, 5, NOW.plusSeconds(600))));

        persister.record(MERCHANT_ID, NOW, 5, LOCKOUT);

        verify(repository, never()).save(any(MerchantCredential.class));
    }
}
