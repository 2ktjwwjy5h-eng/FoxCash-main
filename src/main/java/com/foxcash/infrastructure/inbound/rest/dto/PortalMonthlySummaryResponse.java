package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantWalletUseCase;

import java.math.BigDecimal;

/**
 * Resumo mensal da carteira do lojista (Spec 22): receita bruta (volume
 * APPROVED), liquida (bruta − taxas de transacao do periodo) e contagem.
 */
public record PortalMonthlySummaryResponse(
        BigDecimal grossRevenue,
        BigDecimal netRevenue,
        long transactionCount) {

    public static PortalMonthlySummaryResponse from(MerchantWalletUseCase.MonthlySummary summary) {
        return new PortalMonthlySummaryResponse(
                summary.grossRevenue().amount(),
                summary.netRevenue(),
                summary.transactionCount());
    }
}
