package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: dashboard do lojista no portal (Spec 21) — visão geral
 * (somente leitura).
 */
public interface MerchantDashboardUseCase {

    /**
     * Summary do dashboard. {@code profileId} (Spec 124) opcional: null → lojista
     * inteiro (regressão — sem header); presente → só o perfil (header inválido →
     * vazio, nunca vaza).
     */
    Summary summary(UUID merchantId, UUID profileId, Instant from, Instant to);

    List<TimeSeriesPoint> revenueSeries(UUID merchantId, UUID profileId, Instant from, Instant to);

    List<RecentSale> recentSales(UUID merchantId, UUID profileId, int limit);

    /** Pontos de atenção (risco/estado do lojista — dados do lojista, não por perfil). */
    List<AttentionPoint> attentionPoints(UUID merchantId);

    /** Métricas de topo do dashboard (Spec 21). */
    record Summary(Money grossRevenue, long sales, BigDecimal pixConversionRate, int journeyProgress) {
    }

    /** Ponto de série temporal para gráficos (Spec 20 convenção). */
    record TimeSeriesPoint(LocalDate label, Money value) {
    }

    /** Venda recente listada no dashboard (sem PII; buyerId apenas como hash opaco). */
    record RecentSale(UUID transactionId, Money amount, String status, Instant createdAt, String buyerIdHash) {
    }

    /** Alerta de atenção derivado de risco/estado do lojista (Spec 21). */
    record AttentionPoint(String type, String severity, String message) {
    }
}
