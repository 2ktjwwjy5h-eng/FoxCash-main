package com.foxcash.domain.model.merchant;

import com.foxcash.domain.model.Money;

import java.math.BigDecimal;

/**
 * Config fina por lojista (Spec 98) — value object imutável com os overrides
 * opcionais de taxa/limites/rate limit/controle. {@code null} = usa o default
 * global (fallback — nunca menos restritivo por acidente).
 *
 * <p>Sem {@code gatewayRoute}: a rota de gateway é por **perfil de operação**
 * ({@code merchant_profiles}, Spec 122 — dona da entidade); o master console
 * desta spec não gerencia rota (coordenação 2026-08-15).
 */
public record MerchantConfig(
        PixFeeType pixFeeType,
        Money pixFeeFixed,
        BigDecimal pixFeePercent,
        Money pixFeeThreshold,
        Money withdrawalFee,
        Money pixMin,
        Money pixMax,
        Money withdrawalMin,
        Money withdrawalMax,
        Integer rateLimitRps,
        Integer rateLimitRpm,
        boolean apiBlocked,
        boolean honeypot) {

    /** Sem overrides — tudo {@code null}/{@code false} (comportamento global). */
    public static MerchantConfig defaults() {
        return new MerchantConfig(null, null, null, null, null, null, null, null, null,
                null, null, false, false);
    }

    /** Regra de taxa do lojista (Spec 98): fixa até o threshold; % acima. */
    public enum PixFeeType {
        FIXED,
        PERCENT
    }
}
