package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.infrastructure.outbound.persistence.entity.ReconciliationIssueJpaEntity;

/**
 * Mapeia entre o modelo de dominio ReconciliationIssue e a entidade JPA.
 */
public final class ReconciliationIssueMapper {

    private ReconciliationIssueMapper() {
    }

    public static ReconciliationIssueJpaEntity toEntity(ReconciliationIssue issue) {
        return new ReconciliationIssueJpaEntity(
                issue.id(),
                issue.merchantId(),
                issue.expectedBalance().amount(),
                issue.actualBalance().amount(),
                issue.status(),
                issue.detectedAt(),
                issue.resolvedAt());
    }

    public static ReconciliationIssue toDomain(ReconciliationIssueJpaEntity entity) {
        return new ReconciliationIssue(
                entity.getId(),
                entity.getMerchantId(),
                Money.of(entity.getExpectedBalance()),
                Money.of(entity.getActualBalance()),
                entity.getStatus(),
                entity.getDetectedAt(),
                entity.getResolvedAt());
    }
}
