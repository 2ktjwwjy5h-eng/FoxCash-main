package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.profile.MerchantProfile;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantProfileJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link MerchantProfile} e a entidade JPA
 * {@link MerchantProfileJpaEntity} (Spec 122).
 */
public final class MerchantProfileMapper {

    private MerchantProfileMapper() {
    }

    public static MerchantProfileJpaEntity toEntity(MerchantProfile profile) {
        return new MerchantProfileJpaEntity(
                profile.id(),
                profile.merchantId(),
                profile.name(),
                profile.gatewayRoute(),
                profile.scope(),
                profile.isDefault(),
                profile.sortOrder(),
                profile.deletedAt(),
                profile.createdAt(),
                profile.updatedAt());
    }

    public static MerchantProfile toDomain(MerchantProfileJpaEntity entity) {
        return MerchantProfile.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getName(),
                entity.getGatewayRoute(),
                entity.getScope(),
                entity.isDefault(),
                entity.getSortOrder(),
                entity.getDeletedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
