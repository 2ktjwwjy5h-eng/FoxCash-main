package com.foxcash.domain.model.master;

import com.foxcash.domain.model.merchant.MerchantStatus;

import java.time.Instant;

/**
 * Filtros/paginacao da listagem do painel de usuarios do master (Spec 97,
 * D3.1). O filtro {@code risk} e aplicado POS-paginacao: o {@code medPercent}
 * e derivado ao vivo por lojista (nao filtravel em SQL), entao a faixa
 * (baixo/medio/alto) e avaliada em memoria sobre a pagina retornada
 * (N+1 por item — pagina de auditoria de baixa frequencia, padrao D5).
 */
public record MerchantPanelSearchQuery(
        MerchantStatus status,
        String query,
        Instant from,
        Instant to,
        RiskLevel risk,
        int page,
        int size) {

    /** Faixas do filtro {@code risk} sobre o {@code medPercent} (Spec 97 D6). */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }
}
