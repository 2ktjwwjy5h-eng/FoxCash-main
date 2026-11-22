package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response de receivable (contas a receber, Spec 33) — visao do master.
 */
public record ReceivableResponse(
        UUID id,
        UUID merchantId,
        ReceivableOrigin origin,
        BigDecimal amount,
        BigDecimal paidAmount,
        ReceivableStatus status,
        Instant createdAt,
        Instant paidAt) {

    public static ReceivableResponse from(Receivable receivable) {
        return new ReceivableResponse(
                receivable.id(),
                receivable.merchantId(),
                receivable.origin(),
                receivable.amount().amount(),
                receivable.paidAmount().amount(),
                receivable.status(),
                receivable.createdAt(),
                receivable.paidAt());
    }
}
