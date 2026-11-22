package com.foxcash.domain.model.tracking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Resultado de um disparo de tracking (Spec 81/D3) — {@code event_logs}
 * idempotente por {@code (profile_id, event_id)}. Value object imutável;
 * {@code createdAt} preenchido na restauração (leitura do painel).
 */
public record EventLog(
        UUID profileId,
        String eventType,        // LEAD/INITIATE_CHECKOUT/PURCHASE
        UUID eventId,            // id estável do evento do funil (Spec 75)
        EventLogStatus status,   // OK/ERROR
        String responsePayload,
        Instant createdAt) {

    public EventLog {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static EventLog ok(UUID profileId, String eventType, UUID eventId) {
        return new EventLog(profileId, eventType, eventId, EventLogStatus.OK, null, null);
    }

    public static EventLog error(UUID profileId, String eventType, UUID eventId, String responsePayload) {
        return new EventLog(profileId, eventType, eventId, EventLogStatus.ERROR, responsePayload, null);
    }

    /** Restaura do banco (created_at da linha). */
    public static EventLog restore(UUID profileId, String eventType, UUID eventId,
                                   EventLogStatus status, String responsePayload, Instant createdAt) {
        return new EventLog(profileId, eventType, eventId, status, responsePayload, createdAt);
    }
}
