package com.foxcash.application.service;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.infrastructure.config.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do dispatcher de notificacoes (Spec 7/35 — auditoria 2026-08-05; Spec
 * 65 — Fase 3): orquestra a busca dos lotes (limitado por
 * {@code max-batch-size} por iteracao e {@code max-batches-per-run} por run) e
 * delega CADA notificacao ao {@link NotificationProcessor} (transacao propria).
 */
class NotificationDispatcherServiceTest {

    private final NotificationOutboxPort outbox = mock(NotificationOutboxPort.class);
    private final NotificationProcessor processor = mock(NotificationProcessor.class);
    private final NotificationProperties properties = new NotificationProperties();
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private final NotificationDispatcherService service =
            new NotificationDispatcherService(outbox, processor, properties, meterRegistry);

    @Test
    void dispatchesBatchAndCountsOnlySuccessfulDeliveries() {
        OutboxNotification n1 = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p1");
        OutboxNotification n2 = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p2");
        properties.setMaxBatchSize(2);
        // 1o lote entrega n1/n2; 2a iteracao do loop encontra o backlog vazio e para.
        when(outbox.findPendingDue(any(Instant.class), eq(2)))
                .thenReturn(List.of(n1, n2), List.of());
        when(processor.process(n1)).thenReturn(true);
        when(processor.process(n2)).thenReturn(false);

        int delivered = service.dispatchPending();

        assertEquals(1, delivered);
        verify(processor).process(n1);
        verify(processor).process(n2);
        verify(outbox, times(2)).findPendingDue(any(Instant.class), eq(2));
    }

    @Test
    void doesNothingWhenNoPending() {
        when(outbox.findPendingDue(any(Instant.class), anyInt())).thenReturn(List.of());

        int delivered = service.dispatchPending();

        assertEquals(0, delivered);
        verify(processor, never()).process(any());
    }

    @Test
    void forwardsConfiguredBatchSizeLimitToQuery() {
        properties.setMaxBatchSize(7);
        when(outbox.findPendingDue(any(Instant.class), eq(7))).thenReturn(List.of());

        service.dispatchPending();

        verify(outbox).findPendingDue(any(Instant.class), eq(7));
    }

    // ---------- Spec 65 (Fase 3): multiplos lotes por lock ----------

    @Test
    void drainsMultipleBatchesUntilBacklogEmpty() {
        // Backlog de 2 eventos com lote de 1: o loop drena um lote por iteracao
        // ate esvaziar (Spec 65 — dentro do MESMO lock ShedLock).
        OutboxNotification n1 = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p1");
        OutboxNotification n2 = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p2");
        properties.setMaxBatchSize(1);
        when(outbox.findPendingDue(any(Instant.class), eq(1)))
                .thenReturn(List.of(n1), List.of(n2), List.of());
        when(processor.process(n1)).thenReturn(true);
        when(processor.process(n2)).thenReturn(true);

        int delivered = service.dispatchPending();

        assertEquals(2, delivered);
        verify(processor).process(n1);
        verify(processor).process(n2);
        verify(outbox, times(3)).findPendingDue(any(Instant.class), eq(1));
    }

    @Test
    void stopsAtMaxBatchesPerRun() {
        // Backlog interminavel (o mock reentrega n1 a cada lote): o teto da run
        // (max-batches-per-run) corta o loop — nunca drena infinitamente.
        OutboxNotification n1 = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p1");
        properties.setMaxBatchSize(5);
        properties.setMaxBatchesPerRun(2);
        when(outbox.findPendingDue(any(Instant.class), eq(5))).thenReturn(List.of(n1));
        when(processor.process(n1)).thenReturn(true);

        int delivered = service.dispatchPending();

        assertEquals(2, delivered);
        verify(outbox, times(2)).findPendingDue(any(Instant.class), eq(5));
        verify(processor, times(2)).process(n1);
    }

    @Test
    void sentEventsAreNotRequeriedInNextBatch() {
        // O loop re-consulta o backlog a cada lote: o evento ja marcado SENT no
        // 1o lote (n1) NAO volta no 2o — o mock simula o outbox devolvendo apenas
        // o que ainda esta PENDING (n2, retry vencido). SENT e idempotente.
        OutboxNotification n1 = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p1");
        OutboxNotification n2 = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p2");
        properties.setMaxBatchSize(10);
        when(outbox.findPendingDue(any(Instant.class), eq(10)))
                .thenReturn(List.of(n1, n2), List.of(n2), List.of());
        when(processor.process(n1)).thenReturn(true);
        when(processor.process(n2)).thenReturn(true);

        int delivered = service.dispatchPending();

        assertEquals(3, delivered);
        verify(processor, times(1)).process(n1);
        verify(processor, times(2)).process(n2);
    }

    // ---------- Spec 126: gauge de idade do backlog (avaliado no scrape) ----------

    @Test
    void backlogGaugeReflectsOldestPendingAge() {
        when(outbox.findOldestPendingCreatedAt())
                .thenReturn(java.util.Optional.of(Instant.now().minusSeconds(600)));
        when(outbox.findPendingDue(any(Instant.class), anyInt())).thenReturn(List.of());

        service.dispatchPending();

        assertEquals(600, meterRegistry.get("foxcash_outbox_backlog_age_seconds").gauge().value(), 10);
    }

    @Test
    void backlogGaugeIsZeroWhenNoPending() {
        when(outbox.findOldestPendingCreatedAt()).thenReturn(java.util.Optional.empty());
        when(outbox.findPendingDue(any(Instant.class), anyInt())).thenReturn(List.of());

        service.dispatchPending();

        assertEquals(0, meterRegistry.get("foxcash_outbox_backlog_age_seconds").gauge().value(), 0.01);
    }

    // ---------- Spec 134: modo dreno (fila RabbitMQ ativa) ----------

    @Test
    void workerDisabledSkipsProcessing() {
        // Com a fila RabbitMQ ativa e o backlog zerado, o worker não processa —
        // a entrega passa a ser pelo OutboxConsumer (Spec 134). Rollback =
        // religar a flag.
        properties.setWorkerEnabled(false);
        when(outbox.findPendingDue(any(Instant.class), anyInt()))
                .thenReturn(List.of(OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p1")));

        int delivered = service.dispatchPending();

        assertEquals(0, delivered);
        verify(processor, never()).process(any());
    }
}
