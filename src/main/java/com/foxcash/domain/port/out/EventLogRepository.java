package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.tracking.EventLog;
import com.foxcash.domain.model.tracking.EventLogStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: log de disparos de tracking (Spec 81) — idempotente por
 * {@code (profile_id, event_id)}.
 */
public interface EventLogRepository {

    Optional<EventLog> findByProfileIdAndEventId(UUID profileId, UUID eventId);

    /** Upsert idempotente: insere se ausente, atualiza status/payload se presente. */
    EventLog save(EventLog log);

    PageResult<EventLog> findByProfileId(UUID profileId, int page, int size);

    long countByProfileIdAndStatus(UUID profileId, EventLogStatus status);
}
