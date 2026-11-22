package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.merchant.MerchantRecoveryCode;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantRecoveryCodeJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MerchantRecoveryCode} e a entidade
 * JPA {@link MerchantRecoveryCodeJpaEntity} (Spec 94).
 */
public final class MerchantRecoveryCodeMapper {

    private MerchantRecoveryCodeMapper() {
    }

    public static MerchantRecoveryCodeJpaEntity toEntity(MerchantRecoveryCode code) {
        return new MerchantRecoveryCodeJpaEntity(
                code.id(),
                code.methodId(),
                code.codeHash(),
                code.usedAt(),
                code.createdAt());
    }

    public static MerchantRecoveryCode toDomain(MerchantRecoveryCodeJpaEntity entity) {
        return MerchantRecoveryCode.restore(
                entity.getId(),
                entity.getMethodId(),
                entity.getCodeHash(),
                entity.getUsedAt(),
                entity.getCreatedAt());
    }
}
