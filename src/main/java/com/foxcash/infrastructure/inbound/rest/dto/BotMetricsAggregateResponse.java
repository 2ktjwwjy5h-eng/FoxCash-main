package com.foxcash.infrastructure.inbound.rest.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Agregado de métricas (Spec 75/D7): lista por bot + total — usado no portal
 * (bots da loja) e no espelho master (todos os lojistas, com filtros).
 */
public record BotMetricsAggregateResponse(
        List<BotMetricsResponse> bots,
        BotMetricsSummary total) {

    /** Total agregado (sem {@code botId}). */
    public record BotMetricsSummary(
            long leads,
            long vendas,
            BigDecimal receita,
            double conversao) {

        public static BotMetricsSummary of(long leads, long vendas, BigDecimal receita,
                                           double conversao) {
            return new BotMetricsSummary(leads, vendas, receita, conversao);
        }
    }
}
