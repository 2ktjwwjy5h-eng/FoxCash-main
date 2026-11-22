package com.foxcash.application.service;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.infrastructure.config.RabbitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link OutboxConsumer} (Spec 134): busca o evento no DB (fonte da
 * verdade), entrega via {@link NotificationProcessor}; evento ausente → ack;
 * falha de entrega → lança (retry → DLQ).
 */
class OutboxConsumerTest {

    private final NotificationOutboxPort outbox = mock(NotificationOutboxPort.class);
    private final NotificationProcessor processor = mock(NotificationProcessor.class);
    private final RabbitProperties properties = new RabbitProperties();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final OutboxConsumer consumer = new OutboxConsumer(outbox, processor, properties, meterRegistry);

    private OutboxRelay.OutboxMessage message(UUID id) {
        return new OutboxRelay.OutboxMessage(id, UUID.randomUUID(), "SALE_PAID", "{}",
                "TRANSACTION", UUID.randomUUID(), java.time.Instant.now());
    }

    @Test
    void consumesAndDeliversViaProcessor() {
        OutboxNotification notification = OutboxNotification.pending(
                UUID.randomUUID(), "SALE_PAID", "{}");
        when(outbox.findById(notification.id())).thenReturn(Optional.of(notification));
        when(processor.process(notification)).thenReturn(true);

        consumer.consume(message(notification.id()));

        verify(processor).process(notification);
        assertThat(meterRegistry.counter("foxcash_outbox_consumed_total").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("foxcash_outbox_dlq_total").count()).isZero();
    }

    @Test
    void missingNotificationIsAckedSilently() {
        UUID id = UUID.randomUUID();
        when(outbox.findById(id)).thenReturn(Optional.empty());

        consumer.consume(message(id));

        // Já SENT/removido (at-least-once reentregou) — ack sem processar.
        verifyNoInteractions(processor);
        assertThat(meterRegistry.counter("foxcash_outbox_consumed_total").count()).isEqualTo(1);
    }

    @Test
    void deliveryFailureThrowsForRetryAndDlq() {
        OutboxNotification notification = OutboxNotification.pending(
                UUID.randomUUID(), "SALE_PAID", "{}");
        when(outbox.findById(notification.id())).thenReturn(Optional.of(notification));
        when(processor.process(notification)).thenReturn(false);

        // Lança → Spring AMQP reentrega (retry/backoff) e, após N tentativas,
        // rejeita sem requeue → DLQ (dead-letter do RabbitConfig).
        assertThatThrownBy(() -> consumer.consume(message(notification.id())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retry → DLQ");
        assertThat(meterRegistry.counter("foxcash_outbox_dlq_total").count()).isEqualTo(1);
    }
}
