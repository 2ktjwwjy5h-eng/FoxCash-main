package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.profile.MerchantProfile;
import com.foxcash.domain.model.profile.ProfileScope;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantProfileJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.MerchantProfileMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.MerchantProfileJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adaptador JPA do port {@link MerchantProfileRepository} (Spec 122).
 */
@Repository
public class MerchantProfilePersistenceAdapter implements MerchantProfileRepository {

    private final MerchantProfileJpaRepository jpaRepository;

    public MerchantProfilePersistenceAdapter(MerchantProfileJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<MerchantProfile> findByMerchantId(UUID merchantId) {
        return jpaRepository.findByMerchantIdAndDeletedAtIsNullOrderBySortOrderAsc(merchantId)
                .stream().map(MerchantProfileMapper::toDomain).toList();
    }

    @Override
    public Optional<MerchantProfile> findById(UUID id) {
        return jpaRepository.findByIdAndDeletedAtIsNull(id).map(MerchantProfileMapper::toDomain);
    }

    @Override
    public Optional<MerchantProfile> findByIdAndMerchantId(UUID id, UUID merchantId) {
        return jpaRepository.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId)
                .map(MerchantProfileMapper::toDomain);
    }

    @Override
    public Optional<MerchantProfile> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id).map(MerchantProfileMapper::toDomain);
    }

    @Override
    public MerchantProfile save(MerchantProfile profile) {
        return MerchantProfileMapper.toDomain(jpaRepository.save(MerchantProfileMapper.toEntity(profile)));
    }

    @Override
    public Optional<UUID> defaultProfileIdOf(UUID merchantId) {
        return jpaRepository.findByMerchantIdAndIsDefaultTrueAndDeletedAtIsNull(merchantId)
                .map(MerchantProfileJpaEntity::getId);
    }

    @Override
    public Optional<String> gatewayRouteOf(UUID merchantId, UUID profileId) {
        return jpaRepository.findGatewayRouteByMerchantIdAndId(merchantId, profileId);
    }

    @Override
    public Optional<ProfileScope> scopeOf(UUID merchantId, UUID profileId) {
        return jpaRepository.findScopeByMerchantIdAndId(merchantId, profileId);
    }
}
