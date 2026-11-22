package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantJpaEntity;

/**
 * Mapeia entre o modelo de dominio Merchant e a entidade JPA MerchantJpaEntity.
 * O hash do documento (document_hash) e calculado na camada de infraestrutura
 * (adapter, com HMAC-pepper — auditoria 2026-08-05/B-A1) — o dominio nao
 * conhece o hash. Desde a Spec 39 o documento e opcional (null em novos
 * cadastros) — o hash so e calculado quando presente.
 *
 * <p>Spec 98 (V61): a config fina por lojista (taxa/limites/rate limit/
 * api_blocked/honeypot) e restaurada/gravada aqui; {@code phone} (V60, Spec 97)
 * nao faz parte do dominio (e lido/gravado pela config via entity).
 */
public final class MerchantMapper {

    private MerchantMapper() {
    }

    public static MerchantJpaEntity toEntity(Merchant merchant, String documentHash) {
        MerchantJpaEntity entity = new MerchantJpaEntity(
                merchant.id(),
                merchant.name(),
                merchant.email(),
                merchant.document(),
                documentHash,
                merchant.apiKeyHash(),
                merchant.status(),
                merchant.pricingPlan().transactionFee().amount(),
                merchant.pricingPlan().monthlyFee().amount(),
                merchant.walletBalance().amount(),
                merchant.withdrawalsBlocked(),
                merchant.createdAt());
        MerchantConfig config = merchant.config();
        entity.setPixFeeType(config.pixFeeType() == null ? null : config.pixFeeType().name());
        entity.setPixFeeFixed(config.pixFeeFixed() == null ? null : config.pixFeeFixed().amount());
        entity.setPixFeePercent(config.pixFeePercent());
        entity.setPixFeeThreshold(config.pixFeeThreshold() == null ? null : config.pixFeeThreshold().amount());
        entity.setWithdrawalFee(config.withdrawalFee() == null ? null : config.withdrawalFee().amount());
        entity.setPixMin(config.pixMin() == null ? null : config.pixMin().amount());
        entity.setPixMax(config.pixMax() == null ? null : config.pixMax().amount());
        entity.setWithdrawalMin(config.withdrawalMin() == null ? null : config.withdrawalMin().amount());
        entity.setWithdrawalMax(config.withdrawalMax() == null ? null : config.withdrawalMax().amount());
        entity.setRateLimitRps(config.rateLimitRps());
        entity.setRateLimitRpm(config.rateLimitRpm());
        entity.setApiBlocked(config.apiBlocked());
        entity.setHoneypot(config.honeypot());
        // Spec 128 (V75): gestão automática de risco — humano tocou → engine não age.
        entity.setRiskAutoManaged(merchant.riskAutoManaged());
        return entity;
    }

    public static Merchant toDomain(MerchantJpaEntity entity) {
        PricingPlan pricingPlan = new PricingPlan(
                Money.of(entity.getPricingFeePerTransaction()),
                Money.of(entity.getPricingMonthlyFee()));
        return Merchant.restore(
                entity.getId(),
                entity.getName(),
                entity.getEmail(),
                entity.getDocument(),
                entity.getApiKeyHash(),
                entity.getStatus(),
                pricingPlan,
                Money.of(entity.getWalletBalance()),
                entity.isWithdrawalsBlocked(),
                entity.getCreatedAt(),
                toConfig(entity),
                entity.isRiskAutoManaged());
    }

    /** Config fina do lojista a partir da entity (Spec 98 — V61). */
    public static MerchantConfig toConfig(MerchantJpaEntity entity) {
        return new MerchantConfig(
                parsePixFeeType(entity.getPixFeeType()),
                entity.getPixFeeFixed() == null ? null : Money.of(entity.getPixFeeFixed()),
                entity.getPixFeePercent(),
                entity.getPixFeeThreshold() == null ? null : Money.of(entity.getPixFeeThreshold()),
                entity.getWithdrawalFee() == null ? null : Money.of(entity.getWithdrawalFee()),
                entity.getPixMin() == null ? null : Money.of(entity.getPixMin()),
                entity.getPixMax() == null ? null : Money.of(entity.getPixMax()),
                entity.getWithdrawalMin() == null ? null : Money.of(entity.getWithdrawalMin()),
                entity.getWithdrawalMax() == null ? null : Money.of(entity.getWithdrawalMax()),
                entity.getRateLimitRps(),
                entity.getRateLimitRpm(),
                entity.isApiBlocked(),
                entity.isHoneypot());
    }

    private static MerchantConfig.PixFeeType parsePixFeeType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MerchantConfig.PixFeeType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // Dado legado inválido: trata como sem override (nunca derruba leitura).
            return null;
        }
    }
}
