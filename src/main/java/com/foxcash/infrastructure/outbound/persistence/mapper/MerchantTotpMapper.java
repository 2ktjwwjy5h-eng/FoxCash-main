package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.merchant.MerchantTotp;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantTotpJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MerchantTotp} e a entidade JPA
 * {@link MerchantTotpJpaEntity} (Spec 94). O {@code secretEncrypted} é
 * criptografado em repouso pelo {@code CryptoConverter} (aplicado na entidade) —
 * o domínio nunca vê o valor em claro fora do momento da verificação.
 */
public final class MerchantTotpMapper {

    private MerchantTotpMapper() {
    }

    public static MerchantTotpJpaEntity toEntity(MerchantTotp totp) {
        return new MerchantTotpJpaEntity(
                totp.methodId(),
                totp.secretEncrypted(),
                totp.lastCodeHash(),
                totp.failedAttempts(),
                totp.lockedUntil());
    }

    public static MerchantTotp toDomain(MerchantTotpJpaEntity entity) {
        return MerchantTotp.restore(
                entity.getMethodId(),
                entity.getSecretEncrypted(),
                entity.getLastCodeHash(),
                entity.getFailedAttempts(),
                entity.getLockedUntil());
    }
}
