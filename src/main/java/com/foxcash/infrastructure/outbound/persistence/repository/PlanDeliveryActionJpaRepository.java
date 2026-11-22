package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.PlanDeliveryActionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para ações de entrega configuradas
 * no plano (Spec 49).
 */
public interface PlanDeliveryActionJpaRepository extends JpaRepository<PlanDeliveryActionJpaEntity, UUID> {

    List<PlanDeliveryActionJpaEntity> findByPlanIdOrderBySeqAsc(UUID planId);

    Optional<PlanDeliveryActionJpaEntity> findByIdAndPlanId(UUID actionId, UUID planId);

    boolean existsByPlanId(UUID planId);
}
