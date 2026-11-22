package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.BillingPreviewUseCase;

import java.math.BigDecimal;

/**
 * Response da previsao da fatura mensal.
 */
public record BillingPreviewResponse(
        int totalTransactions,
        BigDecimal variableFee,
        BigDecimal fixedFee,
        BigDecimal totalDue,
        String calculation) {

    public static BillingPreviewResponse from(BillingPreviewUseCase.Preview preview) {
        return new BillingPreviewResponse(
                preview.totalTransactions(),
                preview.variableFee().amount(),
                preview.fixedFee().amount(),
                preview.totalDue().amount(),
                preview.calculation());
    }
}
