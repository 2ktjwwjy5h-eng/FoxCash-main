package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.ChargebackJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para chargebacks (Spec 19).
 */
public interface ChargebackJpaRepository extends JpaRepository<ChargebackJpaEntity, UUID> {

    Optional<ChargebackJpaEntity> findByTransactionId(UUID transactionId);

    long countByMerchantId(UUID merchantId);

    long countByMerchantIdAndCreatedAtAfter(UUID merchantId, Instant createdAt);

    long countByBuyerIdHashAndCreatedAtAfter(String buyerIdHash, Instant createdAt);

    List<ChargebackJpaEntity> findTop100ByOrderByCreatedAtDesc();
}
