package com.foxcash.domain.port.out;

import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantConfig;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: config fina por lojista (Spec 98) — leitura dos overrides
 * pelos pontos de runtime (PricingCalculator, rate limit, api_blocked,
 * honeypot) e gravacao pelo {@code MerchantConfigService}.
 *
 * <p><b>Sem {@code gatewayRoute}</b> — a rota de gateway é por perfil de
 * operação (Spec 122, {@code merchant_profiles}); o master console desta spec
 * não gerencia rota (coordenação 2026-08-15).
 */
public interface MerchantConfigProvider {

    /**
     * Config efetiva do lojista (overrides + flags de controle); vazio → 404
     * {@code merchant_not_found} no service. {@code null} nos campos = usa o
     * default global.
     */
    Optional<Merchant> findWithConfig(UUID merchantId);

    /** Leitura com LOCK PESSIMISTA (SEC-19): toda escrita do lojista reusa o
     * {@code findByIdForUpdate} — o save grava TODOS os campos (sem
     * {@code @DynamicUpdate}), incluindo {@code wallet_balance}; sem o lock, uma
     * snapshot stale sobrescreveria débito/crédito concorrente. */
    Optional<Merchant> findWithConfigForUpdate(UUID merchantId);

    /** Config (value object) do lojista; vazio quando não existe o lojista. */
    Optional<MerchantConfig> config(UUID merchantId);

    /** Rate limit por lojista (rps/rpm); vazio → usa o teto global (fail-closed). */
    Optional<RateLimitOverride> rateLimit(UUID merchantId);

    /** Regra de taxa Pix por lojista; vazio → taxa do plano (global). */
    Optional<MerchantConfig> pixFee(UUID merchantId);

    /** Grava o lojista (config + flags + wallet) — usado pelo service. */
    Merchant save(Merchant merchant);

    /** Override de rate limit por lojista (Spec 98 — D4.3). */
    record RateLimitOverride(Integer rps, Integer rpm) {

        /** Sem override (nenhum dos dois) → teto global. */
        public boolean isPresent() {
            return rps != null || rpm != null;
        }
    }
}
