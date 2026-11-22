package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.GiftPresetJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) do catalogo de Gift PIX — Spec 23.
 */
public interface GiftPresetJpaRepository extends JpaRepository<GiftPresetJpaEntity, UUID> {

    List<GiftPresetJpaEntity> findByMerchantIdOrderByAmountAsc(UUID merchantId);

    Optional<GiftPresetJpaEntity> findByIdAndMerchantId(UUID id, UUID merchantId);
}
