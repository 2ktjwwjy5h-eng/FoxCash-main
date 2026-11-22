package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.StoreButtonJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para botões do menu da loja
 * (Spec 50).
 */
public interface StoreButtonJpaRepository extends JpaRepository<StoreButtonJpaEntity, UUID> {

    Optional<StoreButtonJpaEntity> findByIdAndStoreId(UUID buttonId, UUID storeId);

    List<StoreButtonJpaEntity> findByStoreIdOrderByPositionAsc(UUID storeId);

    List<StoreButtonJpaEntity> findByStoreIdAndActiveTrueOrderByPositionAsc(UUID storeId);

    long countByStoreId(UUID storeId);
}
