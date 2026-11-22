package com.foxcash.application.service;

import com.foxcash.domain.model.notification.OutboxNotification;

/**
 * Evento Spring emitido pelo {@link WebhookEventPublisher} quando um evento é
 * gravado no outbox DB (Spec 134). O {@link OutboxRelay} escuta com
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)}:
 * depois que a transação de negócio commitou, o evento é publicado na fila
 * RabbitMQ. Se o broker falhar, a mensagem permanece no outbox DB e o worker
 * atual (modo dreno) entrega — fail-safe ADR-010, zero perda.
 *
 * <p>O registro é imutável: o consumer reusa o {@code OutboxNotification} do DB
 * (fonte da verdade), nunca o payload da fila.
 */
public record OutboxRecordedEvent(OutboxNotification notification) {
}
