package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.merchant.MerchantMfaMethod;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantMfaMethodJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MerchantMfaMethod} e a entidade JPA
 * {@link MerchantMfaMethodJpaEntity} (Spec 94).
 */
public final class MerchantMfaMethodMapper {

    private MerchantMfaMethodMapper() {
    }

    public static MerchantMfaMethodJpaEntity toEntity(MerchantMfaMethod method) {
        return new MerchantMfaMethodJpaEntity(
                method.id(),
                method.merchantId(),
                method.type(),
                method.status(),
                method.createdAt(),
                method.activatedAt(),
                method.expiresAt());
    }

    public static MerchantMfaMethod toDomain(MerchantMfaMethodJpaEntity entity) {
        return MerchantMfaMethod.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getType(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getActivatedAt(),
                entity.getExpiresAt());
    }
}
