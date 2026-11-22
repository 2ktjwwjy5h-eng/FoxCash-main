package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantSalesUseCase;

import java.math.BigDecimal;

/**
 * Métricas de topo da tela Vendas (Spec 25) — volume aprovado em N dias,
 * vendas concluídas, pendentes e taxa de conversão. Alimenta os MetricCards
 * do frontend (convenção da Spec 20).
 */
public record PortalSalesMetricsResponse(
        BigDecimal volume30d,
        long completedSales,
        long pendingSales,
        BigDecimal conversionRate) {

    public static PortalSalesMetricsResponse from(MerchantSalesUseCase.Metrics metrics) {
        return new PortalSalesMetricsResponse(
                metrics.volume30d(), metrics.completedSales(),
                metrics.pendingSales(), metrics.conversionRate());
    }
}
