package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.delivery.DeliveryAccessLog;
import com.foxcash.domain.port.out.DeliveryAccessLogRepository;
import com.foxcash.infrastructure.outbound.persistence.mapper.DeliveryAccessLogMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.DeliveryAccessLogJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador JPA do port de logs de acesso ao conteúdo (Spec 49+, 2026-08-06).
 */
@Repository
public class DeliveryAccessLogPersistenceAdapter implements DeliveryAccessLogRepository {

    private final DeliveryAccessLogJpaRepository jpaRepository;

    public DeliveryAccessLogPersistenceAdapter(DeliveryAccessLogJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public DeliveryAccessLog save(DeliveryAccessLog log) {
        return DeliveryAccessLogMapper.toDomain(
                jpaRepository.save(DeliveryAccessLogMapper.toEntity(log)));
    }

    @Override
    public List<DeliveryAccessLog> findByDeliveryIdOrderByAccessedAt(UUID deliveryId) {
        return jpaRepository.findByDeliveryIdOrderByAccessedAtAsc(deliveryId).stream()
                .map(DeliveryAccessLogMapper::toDomain)
                .toList();
    }
}
