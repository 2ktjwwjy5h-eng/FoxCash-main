package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.PlanJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para planos da loja (Spec 49).
 */
public interface PlanJpaRepository extends JpaRepository<PlanJpaEntity, UUID> {

    Optional<PlanJpaEntity> findByIdAndStoreId(UUID planId, UUID storeId);

    List<PlanJpaEntity> findByStoreIdOrderByCreatedAtDesc(UUID storeId);

    List<PlanJpaEntity> findByStoreIdAndActiveTrueOrderByCreatedAtDesc(UUID storeId);
}
