package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.tracking.EventLog;
import com.foxcash.domain.model.tracking.EventLogStatus;
import com.foxcash.domain.port.out.EventLogRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.EventLogJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.EventLogMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.EventLogJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link EventLogRepository} (Spec 81) — upsert
 * idempotente por {@code (profile_id, event_id)}.
 */
@Repository
public class EventLogPersistenceAdapter implements EventLogRepository {

    private final EventLogJpaRepository jpaRepository;

    public EventLogPersistenceAdapter(EventLogJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<EventLog> findByProfileIdAndEventId(UUID profileId, UUID eventId) {
        return jpaRepository.findByProfileIdAndEventId(profileId, eventId)
                .map(EventLogMapper::toDomain);
    }

    @Override
    public EventLog save(EventLog log) {
        EventLogJpaEntity entity = jpaRepository.findByProfileIdAndEventId(
                        log.profileId(), log.eventId())
                .map(existing -> {
                    existing.updateResult(log.status(), log.responsePayload());
                    return existing;
                })
                .orElseGet(() -> EventLogMapper.toEntity(log));
        return EventLogMapper.toDomain(jpaRepository.save(entity));
    }

    @Override
    public PageResult<EventLog> findByProfileId(UUID profileId, int page, int size) {
        Page<EventLogJpaEntity> result = jpaRepository
                .findByProfileIdOrderByCreatedAtDesc(profileId, PageRequest.of(page, size));
        return PageResult.of(result.getContent().stream().map(EventLogMapper::toDomain).toList(),
                page, size, result.getTotalElements());
    }

    @Override
    public long countByProfileIdAndStatus(UUID profileId, EventLogStatus status) {
        return jpaRepository.countByProfileIdAndStatus(profileId, status);
    }
}
