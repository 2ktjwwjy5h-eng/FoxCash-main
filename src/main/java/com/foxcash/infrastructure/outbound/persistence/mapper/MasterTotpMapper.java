package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.master.MasterTotp;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterTotpJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link MasterTotp} e a entidade JPA
 * {@link MasterTotpJpaEntity}. O {@code secretEncrypted} é criptografado em
 * repouso pelo {@code CryptoConverter} (aplicado na entidade) — o domínio nunca
 * vê o valor em claro fora do momento da verificação.
 */
public final class MasterTotpMapper {

    private MasterTotpMapper() {
    }

    public static MasterTotpJpaEntity toEntity(MasterTotp totp) {
        return new MasterTotpJpaEntity(
                totp.methodId(),
                totp.secretEncrypted(),
                totp.lastCodeHash(),
                totp.failedAttempts(),
                totp.lockedUntil());
    }

    public static MasterTotp toDomain(MasterTotpJpaEntity entity) {
        return MasterTotp.restore(
                entity.getMethodId(),
                entity.getSecretEncrypted(),
                entity.getLastCodeHash(),
                entity.getFailedAttempts(),
                entity.getLockedUntil());
    }
}
