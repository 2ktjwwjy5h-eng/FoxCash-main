package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.tracking.EventLogStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.EventLogJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório JPA do log de disparos de tracking (Spec 81) — idempotência por
 * {@code (profile_id, event_id)}.
 */
public interface EventLogJpaRepository extends JpaRepository<EventLogJpaEntity, Long> {

    Optional<EventLogJpaEntity> findByProfileIdAndEventId(UUID profileId, UUID eventId);

    Page<EventLogJpaEntity> findByProfileIdOrderByCreatedAtDesc(UUID profileId, Pageable pageable);

    long countByProfileIdAndStatus(UUID profileId, EventLogStatus status);
}
