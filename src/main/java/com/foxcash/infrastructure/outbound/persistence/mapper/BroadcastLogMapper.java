package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.infrastructure.outbound.persistence.entity.BroadcastLogJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link BroadcastLog} e a entidade JPA
 * {@link BroadcastLogJpaEntity} (Spec 79). O {@code id} (BIGSERIAL) é gerado
 * pelo banco na persistência.
 */
public final class BroadcastLogMapper {

    private BroadcastLogMapper() {
    }

    public static BroadcastLogJpaEntity toEntity(BroadcastLog log) {
        return new BroadcastLogJpaEntity(
                log.broadcastId(),
                log.recipientId(),
                log.status(),
                log.errorMessage(),
                log.sentAt());
    }

    public static BroadcastLog toDomain(BroadcastLogJpaEntity entity) {
        return BroadcastLog.restore(entity.getBroadcastId(), entity.getRecipientId(),
                entity.getStatus(), entity.getErrorMessage(), entity.getSentAt());
    }
}
