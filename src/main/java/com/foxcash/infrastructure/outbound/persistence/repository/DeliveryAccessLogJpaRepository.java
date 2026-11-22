package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.DeliveryAccessLogJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para logs de acesso ao conteúdo
 * (Spec 49+, 2026-08-06).
 */
public interface DeliveryAccessLogJpaRepository extends JpaRepository<DeliveryAccessLogJpaEntity, UUID> {

    List<DeliveryAccessLogJpaEntity> findByDeliveryIdOrderByAccessedAtAsc(UUID deliveryId);
}
