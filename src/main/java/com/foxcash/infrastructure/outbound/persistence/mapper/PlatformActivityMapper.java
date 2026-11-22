package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.infrastructure.outbound.persistence.entity.PlatformActivityJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link PlatformActivity} e a entidade JPA
 * {@link PlatformActivityJpaEntity} (Spec 41).
 */
public final class PlatformActivityMapper {

    private PlatformActivityMapper() {
    }

    public static PlatformActivityJpaEntity toEntity(PlatformActivity activity) {
        return new PlatformActivityJpaEntity(
                activity.id(),
                activity.actorType(),
                activity.actorId(),
                activity.action(),
                activity.entityType(),
                activity.entityId(),
                activity.merchantId(),
                activity.details(),
                activity.ip(),
                activity.createdAt());
    }

    public static PlatformActivity toDomain(PlatformActivityJpaEntity entity) {
        return new PlatformActivity(
                entity.getId(),
                entity.getActorType(),
                entity.getActorId(),
                entity.getAction(),
                entity.getEntityType(),
                entity.getEntityId(),
                entity.getMerchantId(),
                entity.getDetails(),
                entity.getIp(),
                entity.getCreatedAt());
    }
}
