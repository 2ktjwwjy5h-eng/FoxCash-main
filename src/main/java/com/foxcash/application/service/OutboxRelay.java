package com.foxcash.application.service;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.infrastructure.config.RabbitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Relay do outbox → fila RabbitMQ (Spec 134). Escuta {@link OutboxRecordedEvent}
 * emitido pelo {@link WebhookEventPublisher} e publica o evento na fila —
 * {@code AFTER_COMMIT}: só roda DEPOIS que a transação de negócio commitou
 * (evento não-commitado nunca vai para a fila).
 *
 * <p><b>Fail-safe (ADR-010):</b> se o publish falhar (broker down/overload), o
 * erro é capturado + WARN e a mensagem PERMANECE no outbox DB — o worker atual
 * ({@link NotificationDispatcherService}, modo dreno) entrega depois. Zero
 * perda: o outbox DB continua sendo a fonte da verdade transacional.
 *
 * <p>Métrica {@code foxcash_outbox_published_total} (Spec 126/134).
 */
@Component
@ConditionalOnProperty(name = "foxcash.rabbit.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final RabbitTemplate rabbitTemplate;
    private final RabbitProperties properties;
    private final MeterRegistry meterRegistry;

    public OutboxRelay(RabbitTemplate rabbitTemplate, RabbitProperties properties,
                       MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOutboxRecorded(OutboxRecordedEvent event) {
        OutboxNotification notification = event.notification();
        try {
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getRoutingKey(),
                    new OutboxMessage(notification.id(), notification.merchantId(),
                            notification.type(), notification.payload(),
                            notification.entityType(), notification.entityId(),
                            Instant.now()));
            meterRegistry.counter("foxcash_outbox_published_total").increment();
        } catch (AmqpException | IllegalStateException e) {
            // Fail-safe: broker indisponível → a mensagem fica no outbox DB e o
            // worker (modo dreno) entrega. Nunca derruba o request de origem.
            log.warn("OutboxRelay: publish RabbitMQ falhou (evento {} fica no outbox DB): {}",
                    notification.id(), e.getMessage());
        }
    }

    /** Envelope da fila — id do outbox + dados para o consumer buscar no DB. */
    public record OutboxMessage(UUID outboxId, UUID merchantId, String type, String payload,
                                String entityType, UUID entityId, Instant publishedAt) {
    }
}
