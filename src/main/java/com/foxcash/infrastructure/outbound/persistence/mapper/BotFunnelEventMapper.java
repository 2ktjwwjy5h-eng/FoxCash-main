package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.botfunnel.BotFunnelEvent;
import com.foxcash.infrastructure.outbound.persistence.entity.BotFunnelEventJpaEntity;

import java.util.UUID;

/**
 * Mapeia entre o modelo de domínio {@link BotFunnelEvent} e a entidade JPA
 * {@link BotFunnelEventJpaEntity} (Spec 75).
 */
public final class BotFunnelEventMapper {

    private BotFunnelEventMapper() {
    }

    public static BotFunnelEventJpaEntity toEntity(BotFunnelEvent event) {
        return new BotFunnelEventJpaEntity(UUID.randomUUID(), event.botId(),
                event.platformUserId(), event.event(), event.transactionId(), event.occurredAt());
    }

    public static BotFunnelEvent toDomain(BotFunnelEventJpaEntity entity) {
        return BotFunnelEvent.restore(entity.getBotId(), entity.getPlatformUserId(),
                entity.getEvent(), entity.getTransactionId(), entity.getOccurredAt());
    }
}
