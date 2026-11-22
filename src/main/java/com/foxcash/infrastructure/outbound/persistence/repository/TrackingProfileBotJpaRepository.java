package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.TrackingProfileBotJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.TrackingProfileBotJpaEntity.TrackingProfileBotId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório JPA do vínculo perfil ↔ bot (Spec 81).
 */
public interface TrackingProfileBotJpaRepository
        extends JpaRepository<TrackingProfileBotJpaEntity, TrackingProfileBotId> {

    List<TrackingProfileBotJpaEntity> findById_ProfileId(UUID profileId);

    void deleteById_ProfileId(UUID profileId);
}
