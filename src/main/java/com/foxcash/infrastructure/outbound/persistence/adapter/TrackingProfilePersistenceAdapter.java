package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.port.out.TrackingProfileRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.TrackingProfileMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.TrackingProfileJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link TrackingProfileRepository} (Spec 81).
 */
@Repository
public class TrackingProfilePersistenceAdapter implements TrackingProfileRepository {

    private final TrackingProfileJpaRepository jpaRepository;

    public TrackingProfilePersistenceAdapter(TrackingProfileJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public TrackingProfile save(TrackingProfile profile) {
        return TrackingProfileMapper.toDomain(
                jpaRepository.save(TrackingProfileMapper.toEntity(profile)));
    }

    @Override
    public Optional<TrackingProfile> findById(UUID id) {
        return jpaRepository.findById(id).map(TrackingProfileMapper::toDomain);
    }

    @Override
    public Optional<TrackingProfile> findByIdAndMerchantId(UUID id, UUID merchantId) {
        return jpaRepository.findByIdAndMerchantId(id, merchantId)
                .map(TrackingProfileMapper::toDomain);
    }

    @Override
    public List<TrackingProfile> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId)
                .stream().map(TrackingProfileMapper::toDomain).toList();
    }

    @Override
    public List<TrackingProfile> findByMerchantIdAndBotIds(UUID merchantId, List<UUID> botIds) {
        // Spec 124 (filtro rígido por bot-set): perfis com bot vinculado no
        // bot-set do perfil ativo — lista vazia → vazio (nunca vaza).
        return jpaRepository.findByMerchantIdAndBotIds(merchantId, botIds)
                .stream().map(TrackingProfileMapper::toDomain).toList();
    }

    @Override
    public List<TrackingProfile> findActiveByBotId(UUID botId) {
        return jpaRepository.findActiveByBotId(botId)
                .stream().map(TrackingProfileMapper::toDomain).toList();
    }

    @Override
    public void delete(TrackingProfile profile) {
        jpaRepository.delete(TrackingProfileMapper.toEntity(profile));
    }
}
