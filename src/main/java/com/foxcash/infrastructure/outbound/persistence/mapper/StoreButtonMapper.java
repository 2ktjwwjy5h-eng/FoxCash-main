package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.infrastructure.outbound.persistence.entity.StoreButtonJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link StoreButton} e a entidade JPA
 * {@link StoreButtonJpaEntity} (Spec 50). O payload é criptografado em repouso
 * pela {@code CryptoConverter} (o mapper passa o valor em claro para a
 * entidade).
 */
public final class StoreButtonMapper {

    private StoreButtonMapper() {
    }

    public static StoreButtonJpaEntity toEntity(StoreButton button) {
        return new StoreButtonJpaEntity(
                button.id(),
                button.storeId(),
                button.label(),
                button.action(),
                button.payload(),
                button.position(),
                button.active(),
                button.createdAt());
    }

    public static StoreButton toDomain(StoreButtonJpaEntity entity) {
        return StoreButton.restore(
                entity.getId(),
                entity.getStoreId(),
                entity.getLabel(),
                entity.getAction(),
                entity.getPayload(),
                entity.getPosition(),
                entity.isActive(),
                entity.getCreatedAt());
    }
}
