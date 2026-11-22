package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.master.MasterMfaMethod;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterMfaMethodJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MasterMfaMethod} e a entidade JPA
 * {@link MasterMfaMethodJpaEntity}.
 */
public final class MasterMfaMethodMapper {

    private MasterMfaMethodMapper() {
    }

    public static MasterMfaMethodJpaEntity toEntity(MasterMfaMethod method) {
        return new MasterMfaMethodJpaEntity(
                method.id(),
                method.masterId(),
                method.type(),
                method.status(),
                method.createdAt(),
                method.activatedAt(),
                method.expiresAt());
    }

    public static MasterMfaMethod toDomain(MasterMfaMethodJpaEntity entity) {
        return MasterMfaMethod.restore(
                entity.getId(),
                entity.getMasterId(),
                entity.getType(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getActivatedAt(),
                entity.getExpiresAt());
    }
}
