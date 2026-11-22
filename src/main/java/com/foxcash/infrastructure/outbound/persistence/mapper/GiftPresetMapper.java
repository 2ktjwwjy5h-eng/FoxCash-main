package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.gift.GiftPreset;
import com.foxcash.infrastructure.outbound.persistence.entity.GiftPresetJpaEntity;

/**
 * Mapeia entre o modelo de dominio {@link GiftPreset} e a entidade JPA
 * {@link GiftPresetJpaEntity} (Spec 23).
 */
public final class GiftPresetMapper {

    private GiftPresetMapper() {
    }

    public static GiftPresetJpaEntity toEntity(GiftPreset preset) {
        return new GiftPresetJpaEntity(
                preset.id(),
                preset.merchantId(),
                preset.amount().amount(),
                preset.label(),
                preset.active(),
                preset.createdAt());
    }

    public static GiftPreset toDomain(GiftPresetJpaEntity entity) {
        return GiftPreset.restore(
                entity.getId(),
                entity.getMerchantId(),
                Money.of(entity.getAmount()),
                entity.getLabel(),
                entity.isActive(),
                entity.getCreatedAt());
    }
}
