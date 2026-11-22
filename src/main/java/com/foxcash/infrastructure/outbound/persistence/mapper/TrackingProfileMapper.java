package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.infrastructure.outbound.persistence.entity.TrackingProfileJpaEntity;

/**
 * Mapeia o aggregate {@link TrackingProfile} (domínio) e a entidade JPA (Spec 81).
 * Os tokens em claro do domínio são criptografados em repouso pelo
 * {@code CryptoConverter} no @Convert da entidade.
 */
public final class TrackingProfileMapper {

    private TrackingProfileMapper() {
    }

    public static TrackingProfileJpaEntity toEntity(TrackingProfile profile) {
        return new TrackingProfileJpaEntity(
                profile.id(), profile.merchantId(), profile.name(), profile.status(),
                profile.facebookPixelId(), profile.facebookAccessToken(),
                profile.tiktokPixelId(), profile.tiktokAccessToken(),
                profile.utmifyToken(), profile.otimizeyToken(),
                profile.createdAt(), profile.updatedAt());
    }

    public static TrackingProfile toDomain(TrackingProfileJpaEntity entity) {
        return TrackingProfile.restore(
                entity.getId(), entity.getMerchantId(), entity.getName(), entity.getStatus(),
                entity.getFacebookPixelId(), entity.getFacebookAccessToken(),
                entity.getTiktokPixelId(), entity.getTiktokAccessToken(),
                entity.getUtmifyToken(), entity.getOtimizeyToken(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
