package com.foxcash.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Publica eventos de dominio no outbox (Spec 35) — chamado DENTRO do
 * {@code @Transactional} do fluxo de origem (nunca em transacao nova), para o
 * evento ser gravado junto com a mutacao.
 *
 * <p>Payload padronizado: {@code {eventId, type, merchantId, createdAt,
 * data:{...}}} — {@code eventId} unico permite deduplicacao no receptor.
 *
 * <p>Spec 134 (fila dedicada): após gravar no outbox DB, emite
 * {@link OutboxRecordedEvent} (Spring). O {@link OutboxRelay} escuta com
 * {@code AFTER_COMMIT} — publica na fila RabbitMQ só depois que a transação de
 * negócio commitou (nada de fila com evento não-commitado). Se o broker falhar,
 * a mensagem permanece no outbox DB e o worker atual (modo dreno) entrega —
 * fail-safe ADR-010.
 */
@Service
public class WebhookEventPublisher {

    private final NotificationOutboxPort outbox;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public WebhookEventPublisher(NotificationOutboxPort outbox, ObjectMapper objectMapper,
                                 ApplicationEventPublisher eventPublisher) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public void publish(UUID merchantId, WebhookEventType type, Object data) {
        publish(merchantId, type, data, null, null);
    }

    /**
     * Publica com a referência de origem (Spec 125 — trace): {@code entityType}/
     * {@code entityId} (ex.: {@code TRANSACTION}/{@code transaction.id}) permitem
     * ao trace listar a entrega do webhook por transação.
     */
    public void publish(UUID merchantId, WebhookEventType type, Object data,
                        String entityType, UUID entityId) {
        try {
            String payload = objectMapper.writeValueAsString(
                    new WebhookEnvelope(UUID.randomUUID(), type, merchantId, Instant.now(), data));
            OutboxNotification notification = OutboxNotification.pending(merchantId, type.name(), payload)
                    .withReference(entityType, entityId);
            outbox.savePending(notification);
            // Spec 134: relay publica na fila APÓS o commit desta transação.
            eventPublisher.publishEvent(new OutboxRecordedEvent(notification));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar evento " + type, e);
        }
    }

    /** Envelope JSON padrao de todos os eventos do outbox (Spec 35). */
    public record WebhookEnvelope(UUID eventId, WebhookEventType type, UUID merchantId,
                                  Instant createdAt, Object data) {
    }
}
