package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MasterRevenueUseCase;

import java.math.BigDecimal;

/**
 * Resumo da receita/perda da plataforma (Spec 34) — response de
 * {@code GET /revenue/summary}. {@code net = entradas − perdas}.
 */
public record RevenueSummaryResponse(
        BigDecimal transactionFees,
        BigDecimal monthlyFees,
        BigDecimal recoveries,
        BigDecimal adminRecoveries,
        BigDecimal withdrawalFees,
        BigDecimal losses,
        BigDecimal total,
        BigDecimal net) {

    public static RevenueSummaryResponse from(MasterRevenueUseCase.RevenueSummary summary) {
        return new RevenueSummaryResponse(
                summary.transactionFees(),
                summary.monthlyFees(),
                summary.recoveries(),
                summary.adminRecoveries(),
                summary.withdrawalFees(),
                summary.losses(),
                summary.total(),
                summary.net());
    }
}
