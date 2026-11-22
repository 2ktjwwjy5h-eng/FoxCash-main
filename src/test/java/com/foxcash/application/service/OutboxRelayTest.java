package com.foxcash.application.service;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.infrastructure.config.RabbitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Testes do {@link OutboxRelay} (Spec 134): publica na fila RabbitMQ após o
 * commit; falha do broker → fail-safe (mensagem fica no outbox DB, nada é
 * lançado ao request de origem).
 */
class OutboxRelayTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitProperties properties = new RabbitProperties();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final OutboxRelay relay = new OutboxRelay(rabbitTemplate, properties, meterRegistry);

    private OutboxNotification notification() {
        return OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "{\"eventId\":\"e1\"}")
                .withReference("TRANSACTION", UUID.randomUUID());
    }

    @Test
    void publishesToExchangeAfterCommit() {
        OutboxNotification notification = notification();

        relay.onOutboxRecorded(new OutboxRecordedEvent(notification));

        verify(rabbitTemplate).convertAndSend(eq(properties.getExchange()), eq(properties.getRoutingKey()),
                any(OutboxRelay.OutboxMessage.class));
        assertThat(meterRegistry.counter("foxcash_outbox_published_total").count()).isEqualTo(1);
    }

    @Test
    void brokerDownIsFailSafeAndDoesNotThrow() {
        doThrow(new AmqpConnectException(new java.io.IOException("broker down")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(OutboxRelay.OutboxMessage.class));

        // Fail-safe ADR-010: nunca derruba o request de origem — a mensagem
        // permanece no outbox DB (worker atual entrega depois).
        assertThatCode(() -> relay.onOutboxRecorded(new OutboxRecordedEvent(notification())))
                .doesNotThrowAnyException();
        assertThat(meterRegistry.counter("foxcash_outbox_published_total").count()).isZero();
    }

    @Test
    void messageCarriesOutboxIdAndMetadata() {
        OutboxNotification notification = notification();

        relay.onOutboxRecorded(new OutboxRecordedEvent(notification));

        var captor = org.mockito.ArgumentCaptor.forClass(OutboxRelay.OutboxMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(properties.getExchange()), eq(properties.getRoutingKey()),
                captor.capture());
        OutboxRelay.OutboxMessage message = captor.getValue();
        assertThat(message.outboxId()).isEqualTo(notification.id());
        assertThat(message.merchantId()).isEqualTo(notification.merchantId());
        assertThat(message.type()).isEqualTo("SALE_PAID");
        assertThat(message.payload()).isEqualTo(notification.payload());
        assertThat(message.entityType()).isEqualTo("TRANSACTION");
        assertThat(message.publishedAt()).isNotNull();
    }

    @Test
    void malformedNotificationStillDoesNotThrow() {
        // Payload nulo/inválido: o publish é tentado; se o converter falhar o
        // erro é capturado (fail-safe) — nunca derruba o fluxo de origem.
        OutboxNotification notification = OutboxNotification.pending(UUID.randomUUID(), "X", null);
        doThrow(new IllegalStateException("converter falhou")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(OutboxRelay.OutboxMessage.class));

        assertThatCode(() -> relay.onOutboxRecorded(new OutboxRecordedEvent(notification)))
                .doesNotThrowAnyException();
    }

    @Test
    void publishedAtIsSet() {
        OutboxRelay.OutboxMessage message = new OutboxRelay.OutboxMessage(
                UUID.randomUUID(), UUID.randomUUID(), "SALE_PAID", "{}", null, null, Instant.now());
        assertThat(message.publishedAt()).isNotNull();
    }
}
