package com.foxcash.application.service;

import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.infrastructure.config.RabbitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumer da fila RabbitMQ do outbox (Spec 134): recebe o envelope do
 * {@link OutboxRelay}, busca o {@code OutboxNotification} no DB (fonte da
 * verdade — nunca usa o payload da fila para mutar) e entrega via
 * {@link NotificationProcessor} (webhook/notificação/tracking) na MESMA lógica
 * do worker atual.
 *
 * <p><b>Retry/backoff + DLQ:</b> o consumidor lança em falha de entrega — o
 * Spring AMQP (default requeue) reentrega com backoff; após N tentativas
 * ({@code foxcash.rabbit.max-attempts}) a mensagem é rejeitada SEM requeue →
 * cai na DLQ (dead-letter configurada no {@code RabbitConfig}). O operador
 * enxerga a DLQ (Spec 67) e pode dar replay.
 *
 * <p>Métricas: {@code foxcash_outbox_consumed_total} e
 * {@code foxcash_outbox_dlq_total} (Spec 126/134). O consumer NÃO marca
 * {@code SENT} — quem marca é o {@link NotificationProcessor} (mesma transação
 * de entrega, dedup por eventId no receptor).
 */
@Component
@ConditionalOnProperty(name = "foxcash.rabbit.enabled", havingValue = "true")
public class OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(OutboxConsumer.class);

    private final NotificationOutboxPort outbox;
    private final NotificationProcessor processor;
    private final RabbitProperties properties;
    private final MeterRegistry meterRegistry;

    public OutboxConsumer(NotificationOutboxPort outbox,
                          NotificationProcessor processor,
                          RabbitProperties properties,
                          MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.processor = processor;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = "${foxcash.rabbit.queue}")
    public void consume(OutboxRelay.OutboxMessage message) {
        meterRegistry.counter("foxcash_outbox_consumed_total").increment();
        var notification = outbox.findById(message.outboxId()).orElse(null);
        if (notification == null) {
            // Já processado/removido — ack silencioso (at-least-once reentregou
            // um evento que o worker DB já marcou SENT).
            log.debug("OutboxConsumer: evento {} não encontrado no DB (já SENT?) — ack", message.outboxId());
            return;
        }
        boolean delivered = processor.process(notification);
        if (!delivered) {
            // Lança → Spring AMQP reentrega (retry/backoff) e, após N tentativas,
            // a mensagem é rejeitada sem requeue → DLQ.
            meterRegistry.counter("foxcash_outbox_dlq_total").increment();
            throw new IllegalStateException("Entrega do evento " + message.outboxId()
                    + " falhou (retry → DLQ após " + properties.getMaxAttempts() + " tentativas)");
        }
    }
}
