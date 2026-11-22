package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.merchant.MerchantSession;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantSessionJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MerchantSession} e a entidade JPA
 * {@link MerchantSessionJpaEntity}.
 */
public final class MerchantSessionMapper {

    private MerchantSessionMapper() {
    }

    public static MerchantSessionJpaEntity toEntity(MerchantSession session) {
        return new MerchantSessionJpaEntity(
                session.id(),
                session.merchantId(),
                session.tokenHash(),
                session.createdAt(),
                session.expiresAt(),
                session.revokedAt());
    }

    public static MerchantSession toDomain(MerchantSessionJpaEntity entity) {
        return MerchantSession.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getTokenHash(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getRevokedAt());
    }
}
