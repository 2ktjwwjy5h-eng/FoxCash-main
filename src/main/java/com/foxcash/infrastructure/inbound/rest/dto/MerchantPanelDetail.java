package com.foxcash.infrastructure.inbound.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Perfil detalhado do lojista no painel de usuarios (Spec 97, D3.2) — KPIs de
 * topo, dados da conta (documento mascarado, telefone opcional) e painel de
 * taxas e limites. {@code pixMin/pixMax/withdrawalMin/withdrawalMax} sao
 * {@code null} ate a Spec 98 configurar os overrides por lojista.
 *
 * <p>{@code fingerprintDistinctMerchants} (Spec 141): quantos lojistas DISTINTOS
 * o dispositivo do lojista tocou (0 = sem fingerprint registrado, 1 = device
 * exclusivo, N&gt;1 = mesmo device em N contas — sinal de risco).
 */
public record MerchantPanelDetail(
        PanelKpis kpis,
        PanelAccount account,
        PanelFeesAndLimits feesAndLimits,
        int fingerprintDistinctMerchants) {

    /** KPIs de topo do lojista (wallet, faturamento, conversao, shops). */
    public record PanelKpis(
            BigDecimal walletBalance,
            BigDecimal grossRevenue,
            long paidPixCount,
            BigDecimal conversionRate,
            long storesCount) {
    }

    /** Dados da conta exibidos no perfil (sem PII em claro). */
    public record PanelAccount(
            String name,
            String email,
            String phone,
            String documentMasked,
            String gateway,
            Instant createdAt) {
    }

    /** Taxas efetivas e limites operacionais do lojista. */
    public record PanelFeesAndLimits(
            BigDecimal transactionFee,
            BigDecimal fixedFee,
            BigDecimal withdrawalFee,
            BigDecimal medPercent,
            long medOccurrences,
            BigDecimal pixMin,
            BigDecimal pixMax,
            BigDecimal withdrawalMin,
            BigDecimal withdrawalMax) {
    }

    public static MerchantPanelDetail of(PanelKpis kpis, PanelAccount account,
                                         PanelFeesAndLimits feesAndLimits) {
        return new MerchantPanelDetail(kpis, account, feesAndLimits, 0);
    }

    public static MerchantPanelDetail of(PanelKpis kpis, PanelAccount account,
                                         PanelFeesAndLimits feesAndLimits,
                                         int fingerprintDistinctMerchants) {
        return new MerchantPanelDetail(kpis, account, feesAndLimits, fingerprintDistinctMerchants);
    }
}
