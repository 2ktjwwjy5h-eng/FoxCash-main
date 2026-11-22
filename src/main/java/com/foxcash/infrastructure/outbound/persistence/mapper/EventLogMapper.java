package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.tracking.EventLog;
import com.foxcash.infrastructure.outbound.persistence.entity.EventLogJpaEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Mapeia o value object {@link EventLog} (domínio) e a entidade JPA (Spec 81).
 */
public final class EventLogMapper {

    private EventLogMapper() {
    }

    public static EventLogJpaEntity toEntity(EventLog log) {
        return new EventLogJpaEntity(null, log.profileId(), log.eventType(), log.eventId(),
                log.status(), log.responsePayload(), Instant.now());
    }

    public static EventLog toDomain(EventLogJpaEntity entity) {
        return EventLog.restore(entity.getProfileId(), entity.getEventType(), entity.getEventId(),
                entity.getStatus(), entity.getResponsePayload(), entity.getCreatedAt());
    }
}
