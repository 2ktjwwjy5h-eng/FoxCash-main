package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.risk.MerchantRiskSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response de snapshot de risco (Spec 16) — dashboard master.
 */
public record MerchantRiskResponse(
        UUID merchantId,
        Instant computedAt,
        BigDecimal approvedVolume,
        long totalTransactions,
        long approvedTransactions,
        long refundedTransactions,
        BigDecimal refundRatePercent,
        BigDecimal chargebackRatePercent,
        int complaintsCount,
        boolean refundAlert,
        boolean chargebackAlert,
        boolean complaintsAlert,
        int riskScore) {

    public static MerchantRiskResponse from(MerchantRiskSnapshot snapshot) {
        return new MerchantRiskResponse(
                snapshot.merchantId(),
                snapshot.computedAt(),
                snapshot.approvedVolume().amount(),
                snapshot.totalTransactions(),
                snapshot.approvedTransactions(),
                snapshot.refundedTransactions(),
                snapshot.refundRatePercent(),
                snapshot.chargebackRatePercent(),
                snapshot.complaintsCount(),
                snapshot.refundAlert(),
                snapshot.chargebackAlert(),
                snapshot.complaintsAlert(),
                snapshot.riskScore());
    }
}
