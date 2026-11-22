package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.infrastructure.outbound.persistence.entity.DeliveryAccessLogJpaEntity;

/**
 * Mapeia entre o modelo de domínio DeliveryAccessLog e a entidade JPA
 * (Spec 49+, 2026-08-06).
 */
public final class DeliveryAccessLogMapper {

    private DeliveryAccessLogMapper() {
    }

    public static DeliveryAccessLogJpaEntity toEntity(DeliveryAccessLog log) {
        return new DeliveryAccessLogJpaEntity(
                log.id(), log.deliveryId(), log.accessedAt(), log.ip(), log.device(), log.note());
    }

    public static DeliveryAccessLog toDomain(DeliveryAccessLogJpaEntity entity) {
        return DeliveryAccessLog.restore(
                entity.getId(), entity.getDeliveryId(), entity.getAccessedAt(),
                entity.getIp(), entity.getDevice(), entity.getNote());
    }
}
