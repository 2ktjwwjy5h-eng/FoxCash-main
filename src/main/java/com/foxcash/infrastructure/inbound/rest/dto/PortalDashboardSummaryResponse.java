package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantDashboardUseCase;

import java.math.BigDecimal;

/**
 * Response do resumo do dashboard do lojista (Spec 21).
 */
public record PortalDashboardSummaryResponse(
        BigDecimal grossRevenue,
        long sales,
        BigDecimal pixConversionRate,
        int journeyProgress) {

    public static PortalDashboardSummaryResponse from(MerchantDashboardUseCase.Summary summary) {
        return new PortalDashboardSummaryResponse(
                summary.grossRevenue().amount(),
                summary.sales(),
                summary.pixConversionRate(),
                summary.journeyProgress());
    }
}
