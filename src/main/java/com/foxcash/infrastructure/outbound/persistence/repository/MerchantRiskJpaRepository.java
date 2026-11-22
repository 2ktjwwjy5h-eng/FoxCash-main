package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MerchantRiskJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para snapshots de risco (Spec 16).
 */
public interface MerchantRiskJpaRepository extends JpaRepository<MerchantRiskJpaEntity, UUID>,
        JpaSpecificationExecutor<MerchantRiskJpaEntity> {

    Optional<MerchantRiskJpaEntity> findTopByMerchantIdOrderByComputedAtDesc(UUID merchantId);
}
