package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.infrastructure.outbound.persistence.entity.StoreMessageJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link StoreMessage} e a entidade JPA
 * {@link StoreMessageJpaEntity} (Spec 51).
 */
public final class StoreMessageMapper {

    private StoreMessageMapper() {
    }

    public static StoreMessageJpaEntity toEntity(StoreMessage message) {
        return new StoreMessageJpaEntity(
                message.id(),
                message.storeId(),
                message.event(),
                message.text(),
                message.active(),
                message.createdAt(),
                message.updatedAt());
    }

    public static StoreMessage toDomain(StoreMessageJpaEntity entity) {
        return StoreMessage.restore(
                entity.getId(),
                entity.getStoreId(),
                entity.getEvent(),
                entity.getText(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
