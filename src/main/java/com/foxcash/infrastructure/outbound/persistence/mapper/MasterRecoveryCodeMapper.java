package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.master.MasterRecoveryCode;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterRecoveryCodeJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MasterRecoveryCode} e a entidade JPA
 * {@link MasterRecoveryCodeJpaEntity}.
 */
public final class MasterRecoveryCodeMapper {

    private MasterRecoveryCodeMapper() {
    }

    public static MasterRecoveryCodeJpaEntity toEntity(MasterRecoveryCode code) {
        return new MasterRecoveryCodeJpaEntity(
                code.id(),
                code.methodId(),
                code.codeHash(),
                code.usedAt(),
                code.createdAt());
    }

    public static MasterRecoveryCode toDomain(MasterRecoveryCodeJpaEntity entity) {
        return MasterRecoveryCode.restore(
                entity.getId(),
                entity.getMethodId(),
                entity.getCodeHash(),
                entity.getUsedAt(),
                entity.getCreatedAt());
    }
}
