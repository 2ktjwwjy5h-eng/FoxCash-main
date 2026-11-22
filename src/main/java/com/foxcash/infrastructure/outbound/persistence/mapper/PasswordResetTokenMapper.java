package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.auth.PasswordResetToken;
import com.foxcash.infrastructure.outbound.persistence.entity.PasswordResetTokenJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link PasswordResetToken} e a entidade JPA
 * {@link PasswordResetTokenJpaEntity} (Spec 40).
 */
public final class PasswordResetTokenMapper {

    private PasswordResetTokenMapper() {
    }

    public static PasswordResetTokenJpaEntity toEntity(PasswordResetToken token) {
        return new PasswordResetTokenJpaEntity(
                token.id(),
                token.merchantId(),
                token.tokenHash(),
                token.expiresAt(),
                token.usedAt(),
                token.createdAt());
    }

    public static PasswordResetToken toDomain(PasswordResetTokenJpaEntity entity) {
        return PasswordResetToken.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getTokenHash(),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getUsedAt());
    }
}
