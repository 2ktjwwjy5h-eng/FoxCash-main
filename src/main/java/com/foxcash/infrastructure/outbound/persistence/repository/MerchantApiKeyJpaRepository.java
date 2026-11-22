package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.MerchantApiKeyJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) de chaves de API do lojista —
 * Spec 24.
 */
public interface MerchantApiKeyJpaRepository extends JpaRepository<MerchantApiKeyJpaEntity, UUID> {

    Optional<MerchantApiKeyJpaEntity> findByKeyHash(String keyHash);

    List<MerchantApiKeyJpaEntity> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    Optional<MerchantApiKeyJpaEntity> findByIdAndMerchantId(UUID id, UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, String status);
}
