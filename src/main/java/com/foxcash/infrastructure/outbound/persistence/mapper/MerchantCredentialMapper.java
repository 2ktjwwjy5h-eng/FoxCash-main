package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.merchant.MerchantCredential;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantCredentialJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MerchantCredential} e a entidade JPA
 * {@link MerchantCredentialJpaEntity}.
 */
public final class MerchantCredentialMapper {

    private MerchantCredentialMapper() {
    }

    public static MerchantCredentialJpaEntity toEntity(MerchantCredential credential) {
        return new MerchantCredentialJpaEntity(
                credential.id(),
                credential.merchantId(),
                credential.passwordHash(),
                credential.createdAt(),
                credential.updatedAt(),
                credential.lastUsedAt(),
                credential.failedLoginAttempts(),
                credential.lockedUntil());
    }

    public static MerchantCredential toDomain(MerchantCredentialJpaEntity entity) {
        return MerchantCredential.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getPasswordHash(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastUsedAt(),
                entity.getFailedLoginAttempts(),
                entity.getLockedUntil());
    }
}
