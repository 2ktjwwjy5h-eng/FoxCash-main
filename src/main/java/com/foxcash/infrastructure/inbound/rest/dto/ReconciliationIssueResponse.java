package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.reconciliation.ReconciliationIssue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Issue de reconciliação (Spec 30) na resposta REST — valores financeiros com
 * escala normalizada 2 (contrato estável para o frontend).
 */
public record ReconciliationIssueResponse(
        UUID issueId,
        UUID merchantId,
        BigDecimal expectedBalance,
        BigDecimal actualBalance,
        String status,
        Instant detectedAt,
        Instant resolvedAt) {

    public static ReconciliationIssueResponse from(ReconciliationIssue issue) {
        return new ReconciliationIssueResponse(
                issue.id(),
                issue.merchantId(),
                issue.expectedBalance().amount(),
                issue.actualBalance().amount(),
                issue.status().name(),
                issue.detectedAt(),
                issue.resolvedAt());
    }
}
