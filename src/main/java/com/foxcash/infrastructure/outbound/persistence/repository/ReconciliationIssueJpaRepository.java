package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.ReconciliationIssueJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) das issues de reconciliação (Spec 30).
 */
public interface ReconciliationIssueJpaRepository
        extends JpaRepository<ReconciliationIssueJpaEntity, UUID>,
        JpaSpecificationExecutor<ReconciliationIssueJpaEntity> {

    /** Issue ABERTA do lojista (unique parcial garante no máximo uma). */
    Optional<ReconciliationIssueJpaEntity> findByMerchantIdAndStatus(
            UUID merchantId, ReconciliationIssueStatus status);
}
