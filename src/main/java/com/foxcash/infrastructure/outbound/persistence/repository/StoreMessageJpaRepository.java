package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.store.StoreMessageEvent;
import com.foxcash.infrastructure.outbound.persistence.entity.StoreMessageJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para mensagens automáticas da loja
 * (Spec 51).
 */
public interface StoreMessageJpaRepository extends JpaRepository<StoreMessageJpaEntity, UUID> {

    Optional<StoreMessageJpaEntity> findByIdAndStoreId(UUID messageId, UUID storeId);

    Optional<StoreMessageJpaEntity> findByStoreIdAndEvent(UUID storeId, StoreMessageEvent event);

    List<StoreMessageJpaEntity> findByStoreIdOrderByEventAsc(UUID storeId);

    List<StoreMessageJpaEntity> findByStoreIdAndActiveTrueOrderByEventAsc(UUID storeId);
}
