package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.port.out.TrackingProfileBotRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.TrackingProfileBotJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.TrackingProfileBotJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Adaptador JPA do port {@link TrackingProfileBotRepository} (Spec 81).
 */
@Repository
public class TrackingProfileBotPersistenceAdapter implements TrackingProfileBotRepository {

    private final TrackingProfileBotJpaRepository jpaRepository;

    public TrackingProfileBotPersistenceAdapter(TrackingProfileBotJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Set<UUID> findBotIdsByProfileId(UUID profileId) {
        return jpaRepository.findById_ProfileId(profileId).stream()
                .map(link -> link.getId().getBotId())
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional
    public void replaceBotLinks(UUID profileId, Collection<UUID> botIds) {
        jpaRepository.deleteById_ProfileId(profileId);
        for (UUID botId : botIds) {
            jpaRepository.save(new TrackingProfileBotJpaEntity(profileId, botId));
        }
    }

    @Override
    @Transactional
    public void deleteByProfileId(UUID profileId) {
        jpaRepository.deleteById_ProfileId(profileId);
    }
}
