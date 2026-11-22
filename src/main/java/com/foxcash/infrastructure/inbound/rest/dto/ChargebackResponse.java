package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.fraud.Chargeback;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response de chargeback (Spec 19) — revisao do master.
 */
public record ChargebackResponse(
        UUID id,
        UUID merchantId,
        UUID transactionId,
        BigDecimal amount,
        String reason,
        Instant createdAt) {

    public static ChargebackResponse from(Chargeback chargeback) {
        return new ChargebackResponse(
                chargeback.id(),
                chargeback.merchantId(),
                chargeback.transactionId(),
                chargeback.amount().amount(),
                chargeback.reason(),
                chargeback.createdAt());
    }
}
