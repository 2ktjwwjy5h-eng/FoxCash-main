package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterSessionJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MasterSession} e a entidade JPA
 * {@link MasterSessionJpaEntity}.
 */
public final class MasterSessionMapper {

    private MasterSessionMapper() {
    }

    public static MasterSessionJpaEntity toEntity(MasterSession session) {
        return new MasterSessionJpaEntity(
                session.id(),
                session.masterId(),
                session.tokenHash(),
                session.createdAt(),
                session.expiresAt(),
                session.revokedAt(),
                session.steppedUpAt());
    }

    public static MasterSession toDomain(MasterSessionJpaEntity entity) {
        return MasterSession.restore(
                entity.getId(),
                entity.getMasterId(),
                entity.getTokenHash(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getRevokedAt(),
                entity.getSteppedUpAt());
    }
}
