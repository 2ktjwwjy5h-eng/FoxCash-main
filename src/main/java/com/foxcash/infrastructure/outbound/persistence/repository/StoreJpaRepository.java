package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.StoreJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para lojas do vendedor (Spec 48).
 */
public interface StoreJpaRepository extends JpaRepository<StoreJpaEntity, UUID> {

    Optional<StoreJpaEntity> findByMerchantId(UUID merchantId);

    Optional<StoreJpaEntity> findByIdAndMerchantId(UUID storeId, UUID merchantId);

    boolean existsByMerchantId(UUID merchantId);
}
