package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.master.Master;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link Master} e a entidade JPA
 * {@link MasterJpaEntity}.
 */
public final class MasterMapper {

    private MasterMapper() {
    }

    public static MasterJpaEntity toEntity(Master master) {
        return new MasterJpaEntity(
                master.id(),
                master.username(),
                master.passwordHash(),
                master.name(),
                master.isActive(),
                master.canManageMasters(),
                master.createdAt(),
                master.failedLoginAttempts(),
                master.lockedUntil());
    }

    public static Master toDomain(MasterJpaEntity entity) {
        return Master.restore(
                entity.getId(),
                entity.getUsername(),
                entity.getPasswordHash(),
                entity.getName(),
                entity.isActive(),
                entity.isCanManageMasters(),
                entity.getCreatedAt(),
                entity.getFailedLoginAttempts(),
                entity.getLockedUntil());
    }
}
