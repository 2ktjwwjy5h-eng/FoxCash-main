package com.foxcash.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Testes do publicador de eventos no outbox (Spec 35): envelope padronizado
 * {@code {eventId, type, merchantId, createdAt, data}} com eventId unico,
 * payload JSON valido e gravacao PENDING na mesma transacao.
 *
 * <p>Spec 134: apos gravar, emite {@link OutboxRecordedEvent} (o relay publica
 * na fila RabbitMQ AFTER_COMMIT).
 */
class WebhookEventPublisherTest {

    private final NotificationOutboxPort outbox = mock(NotificationOutboxPort.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final WebhookEventPublisher publisher = new WebhookEventPublisher(outbox, objectMapper, eventPublisher);

    @Test
    void publishesEnvelopeWithUniqueEventIdAndData() throws Exception {
        UUID merchantId = UUID.randomUUID();

        publisher.publish(merchantId, WebhookEventType.SALE_PAID,
                Map.of("transactionId", "t1", "amount", 150.00));

        ArgumentCaptor<OutboxNotification> captor = ArgumentCaptor.forClass(OutboxNotification.class);
        verify(outbox).savePending(captor.capture());
        OutboxNotification notification = captor.getValue();
        assertThat(notification.merchantId()).isEqualTo(merchantId);
        assertThat(notification.type()).isEqualTo("SALE_PAID");
        assertThat(notification.attempts()).isZero();

        JsonNode envelope = objectMapper.readTree(notification.payload());
        assertThat(envelope.has("eventId")).isTrue();
        assertThat(envelope.get("type").asText()).isEqualTo("SALE_PAID");
        assertThat(envelope.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(envelope.has("createdAt")).isTrue();
        assertThat(envelope.get("data").get("transactionId").asText()).isEqualTo("t1");
        assertThat(envelope.get("data").get("amount").asDouble()).isEqualTo(150.00);

        // Spec 134: após gravar, emite o evento para o relay (AFTER_COMMIT).
        verify(eventPublisher).publishEvent(new OutboxRecordedEvent(notification));
    }

    @Test
    void generatesDifferentEventIdPerPublication() {
        publisher.publish(UUID.randomUUID(), WebhookEventType.CHARGE_CREATED, Map.of());
        publisher.publish(UUID.randomUUID(), WebhookEventType.CHARGE_CREATED, Map.of());

        ArgumentCaptor<OutboxNotification> captor = ArgumentCaptor.forClass(OutboxNotification.class);
        verify(outbox, times(2)).savePending(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0).id())
                .isNotEqualTo(captor.getAllValues().get(1).id());
    }
}
