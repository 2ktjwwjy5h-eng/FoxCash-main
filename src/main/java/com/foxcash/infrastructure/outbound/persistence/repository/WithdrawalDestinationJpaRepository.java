package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.WithdrawalDestinationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) de destinos de saque — Spec 31.
 */
public interface WithdrawalDestinationJpaRepository
        extends JpaRepository<WithdrawalDestinationJpaEntity, UUID> {

    Optional<WithdrawalDestinationJpaEntity> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<WithdrawalDestinationJpaEntity> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, String status);
}
