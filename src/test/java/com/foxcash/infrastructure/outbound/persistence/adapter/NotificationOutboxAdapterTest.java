package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.infrastructure.outbound.persistence.entity.NotificationOutboxJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.NotificationOutboxJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da divisao da query do outbox (causa raiz Fase 2A, docs §11.6): o
 * {@link NotificationOutboxAdapter} busca primeiro os nunca-tentados (FIFO via
 * indice parcial V45) e, se o lote nao encheu, completa com retries vencidos.
 */
class NotificationOutboxAdapterTest {

    private final NotificationOutboxJpaRepository repository = mock(NotificationOutboxJpaRepository.class);
    private final NotificationOutboxAdapter adapter = new NotificationOutboxAdapter(repository);

    private static NotificationOutboxJpaEntity entity(UUID id, Instant nextAttemptAt) {
        return new NotificationOutboxJpaEntity(id, UUID.randomUUID(), "SALE_PAID", "{}",
                "PENDING", Instant.parse("2026-08-09T04:00:00Z"), null, 0, null, nextAttemptAt);
    }

    @Test
    void neverAttemptedFillingBatchDoesNotTouchRetryQuery() {
        UUID firstId = UUID.randomUUID();
        // Lote cheio (limit = 5): o ramo de retries nao deve ser consultado.
        when(repository.findPendingNeverAttempted(eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of(entity(firstId, null), entity(UUID.randomUUID(), null),
                        entity(UUID.randomUUID(), null), entity(UUID.randomUUID(), null),
                        entity(UUID.randomUUID(), null)));

        List<OutboxNotification> result = adapter.findPendingDue(Instant.now(), 5);

        assertThat(result).hasSize(5);
        assertThat(result.get(0).id()).isEqualTo(firstId);
        verify(repository, never()).findPendingRetryDue(any(), any(), any());
    }

    @Test
    void topsUpWithRetryDueWhenNeverAttemptedIsShort() {
        UUID firstId = UUID.randomUUID();
        UUID retryId = UUID.randomUUID();
        when(repository.findPendingNeverAttempted(eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of(entity(firstId, null)));
        when(repository.findPendingRetryDue(eq("PENDING"), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(entity(retryId, Instant.now().minusSeconds(60))));

        List<OutboxNotification> result = adapter.findPendingDue(Instant.now(), 5);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(firstId);
        assertThat(result.get(1).id()).isEqualTo(retryId);
        // O lote restante (5 - 1) e pedido ao ramo de retries.
        verify(repository).findPendingRetryDue(eq("PENDING"), any(Instant.class),
                argThat((Pageable p) -> p.getPageSize() == 4));
    }

    @Test
    void returnsEmptyWhenNoPendingAtAll() {
        when(repository.findPendingNeverAttempted(eq("PENDING"), any(Pageable.class)))
                .thenReturn(List.of());

        List<OutboxNotification> result = adapter.findPendingDue(Instant.now(), 5);

        assertThat(result).isEmpty();
        verify(repository).findPendingRetryDue(eq("PENDING"), any(Instant.class),
                argThat((Pageable p) -> p.getPageSize() == 5));
    }

    @Test
    void incrementAttemptsIsAtomicAndReturnsFreshValue() {
        // SEC-11 (revisão de segurança 2026-08-11): o incremento é ATÔMICO
        // (UPDATE no banco — sem lost update entre processadores concorrentes)
        // e o valor devolvido é a leitura FRESCA após o incremento.
        UUID id = UUID.randomUUID();
        when(repository.incrementAttemptsAtomically(id)).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(
                new NotificationOutboxJpaEntity(id, UUID.randomUUID(), "SALE_PAID", "{}",
                        "PENDING", Instant.parse("2026-08-09T04:00:00Z"), null, 3, null, null)));

        int result = adapter.incrementAttempts(id);

        assertThat(result).isEqualTo(3);
        verify(repository).incrementAttemptsAtomically(id);
        verify(repository).findById(id);
    }
}
