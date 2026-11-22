package com.foxcash.domain.model.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Indicadores de risco do lojista na janela de 30 dias (Spec 128 — Risk Scoring
 * Engine). Mesma fórmula do {@code medPercent} do painel (Spec 97), mas com
 * janela de período (o snapshot {@code merchant_risk} é all-time) e denominador
 * de vendas **que já foram pagas**.
 *
 * <p>{@code medPercent} = {@code (refunded + chargebacks) ÷ paidCount × 100},
 * onde {@code paidCount = approved + refunded} (uma transação estornada JÁ foi
 * paga — sem isso, um lojista com vendas estornadas zeraria o denominador e o
 * fail-safe mascararia o risco; achado do smoke 2026-08-16). 0 (ZERO) quando
 * não há vendas pagas na janela (fail-safe: sem volume recente o engine nunca
 * age — evita falso positivo).
 */
public record MerchantRiskStats(long approved, long refunded, long chargebacks) {

    /** Vendas que já foram pagas na janela (aprovadas + estornadas). */
    public long paidCount() {
        return approved + refunded;
    }

    /** MED (medida de ocorrências problemáticas) em % — 0 sem vendas pagas. */
    public BigDecimal medPercent() {
        if (paidCount() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        return BigDecimal.valueOf(refunded + chargebacks)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(paidCount()), 2, RoundingMode.HALF_EVEN);
    }
}
