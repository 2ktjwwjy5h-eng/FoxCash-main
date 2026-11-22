package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.infrastructure.outbound.persistence.entity.ScheduledMessageJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link ScheduledMessage} e a entidade JPA
 * {@link ScheduledMessageJpaEntity} (Spec 51).
 */
public final class ScheduledMessageMapper {

    private ScheduledMessageMapper() {
    }

    public static ScheduledMessageJpaEntity toEntity(ScheduledMessage message) {
        return new ScheduledMessageJpaEntity(
                message.id(),
                message.storeId(),
                message.text(),
                message.scheduledAt(),
                message.cron(),
                message.audience(),
                message.status(),
                message.sentAt(),
                message.createdAt());
    }

    public static ScheduledMessage toDomain(ScheduledMessageJpaEntity entity) {
        return ScheduledMessage.restore(
                entity.getId(),
                entity.getStoreId(),
                entity.getText(),
                entity.getScheduledAt(),
                entity.getCron(),
                entity.getAudience(),
                entity.getStatus(),
                entity.getSentAt(),
                entity.getCreatedAt());
    }
}
