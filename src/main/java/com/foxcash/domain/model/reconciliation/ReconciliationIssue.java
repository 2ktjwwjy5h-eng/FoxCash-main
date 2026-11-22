package com.foxcash.domain.model.reconciliation;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue de reconciliação (Spec 30): divergência entre {@code merchants.wallet_balance}
 * e o {@code balance_after} da última entry do ledger (Spec 27). Registrada pelo
 * job/manual run para auditoria do master — o master decide a correção (fora de escopo).
 */
public record ReconciliationIssue(
        UUID id,
        UUID merchantId,
        Money expectedBalance,
        Money actualBalance,
        ReconciliationIssueStatus status,
        Instant detectedAt,
        Instant resolvedAt) {

    public ReconciliationIssue {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(expectedBalance, "expectedBalance must not be null");
        Objects.requireNonNull(actualBalance, "actualBalance must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(detectedAt, "detectedAt must not be null");
    }

    public static ReconciliationIssue open(UUID merchantId, Money expectedBalance, Money actualBalance,
                                           Instant now) {
        return new ReconciliationIssue(UUID.randomUUID(), merchantId, expectedBalance, actualBalance,
                ReconciliationIssueStatus.OPEN, now, null);
    }
}
