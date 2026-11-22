package com.foxcash.infrastructure.inbound.rest.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Métricas de um bot (Spec 75/D7) — resposta do portal por bot e da lista do
 * master. {@code merchantId}/{@code merchantName} são {@code null} nas métricas
 * por bot do portal (escopo já é do lojista); preenchidos no espelho master.
 * {@code receita} em escala normalizada (2 casas — padrão do projeto, Spec 30).
 */
public record BotMetricsResponse(
        UUID botId,
        UUID merchantId,
        String merchantName,
        long leads,
        long vendas,
        BigDecimal receita,
        double conversao) {

    public static BotMetricsResponse of(UUID botId, UUID merchantId, String merchantName,
                                        long leads, long vendas, BigDecimal receita,
                                        double conversao) {
        return new BotMetricsResponse(botId, merchantId, merchantName, leads, vendas, receita, conversao);
    }
}
