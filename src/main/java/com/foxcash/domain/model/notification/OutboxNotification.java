package com.foxcash.domain.model.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento de notificacao pendente no outbox (Spec 7/35).
 * A entrega e feita de forma desacoplada por um worker agendado, com retry e
 * backoff exponencial (Spec 35): {@code attempts} conta as tentativas,
 * {@code lastError} guarda o ultimo erro e {@code nextAttemptAt} agenda a
 * proxima tentativa (null quando PENDING novo ou quando ja FAILED).
 *
 * <p>Spec 125 (trace): {@code entityType}/{@code entityId} referenciam a origem
 * do evento (ex.: TRANSACTION + transaction.id) e {@code status} expõe o estado
 * real da entrega (PENDING/SENT/FAILED) para o trace.
 */
public record OutboxNotification(UUID id, UUID merchantId, String type, String payload,
                                 Instant createdAt, int attempts, String lastError,
                                 Instant nextAttemptAt, String entityType, UUID entityId,
                                 String status) {

    public static OutboxNotification pending(UUID merchantId, String type, String payload) {
        return new OutboxNotification(UUID.randomUUID(), merchantId, type, payload,
                Instant.now(), 0, null, null, null, null, "PENDING");
    }

    /** Construtor de conveniencia para eventos sem controle de retry (legado). */
    public OutboxNotification(UUID id, UUID merchantId, String type, String payload, Instant createdAt) {
        this(id, merchantId, type, payload, createdAt, 0, null, null, null, null, "PENDING");
    }

    /** Construtor de conveniencia sem referencia/status (chamadores existentes). */
    public OutboxNotification(UUID id, UUID merchantId, String type, String payload,
                              Instant createdAt, int attempts, String lastError, Instant nextAttemptAt) {
        this(id, merchantId, type, payload, createdAt, attempts, lastError, nextAttemptAt,
                null, null, "PENDING");
    }

    /** Copia com a referência de origem (Spec 125 — trace). */
    public OutboxNotification withReference(String entityType, UUID entityId) {
        return new OutboxNotification(id, merchantId, type, payload, createdAt,
                attempts, lastError, nextAttemptAt, entityType, entityId, status);
    }
}
