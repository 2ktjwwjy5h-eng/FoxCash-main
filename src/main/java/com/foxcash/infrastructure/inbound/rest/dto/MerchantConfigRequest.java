package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.MerchantConfig;

import java.math.BigDecimal;

/**
 * Request PATCH parcial da config fina por lojista (Spec 98 — D5):
 * {@code null} = NÃO alterar o campo (semântica PATCH/Spec 48). O PUT converte
 * para {@link MerchantConfig} (campos ausentes preservam o valor atual) e o
 * {@code Merchant.updateConfig} valida o conjunto resultante. {@code apiBlocked}
 * é {@link Boolean} (nullable) para distinguir "não tocar" de {@code false}.
 */
public record MerchantConfigRequest(
        String pixFeeType,
        BigDecimal pixFeeFixed,
        BigDecimal pixFeePercent,
        BigDecimal pixFeeThreshold,
        BigDecimal withdrawalFee,
        BigDecimal pixMin,
        BigDecimal pixMax,
        BigDecimal withdrawalMin,
        BigDecimal withdrawalMax,
        Integer rateLimitRps,
        Integer rateLimitRpm,
        Boolean apiBlocked,
        Boolean honeypot) {

    /**
     * Mescla a request parcial com a config atual (PATCH) e devolve a config
     * resultante — {@code null} na request preserva o valor vigente.
     */
    public MerchantConfig mergeWith(MerchantConfig current) {
        return new MerchantConfig(
                pixFeeType != null
                        ? parsePixFeeType(pixFeeType)
                        : current.pixFeeType(),
                pixFeeFixed != null ? Money.of(pixFeeFixed) : current.pixFeeFixed(),
                pixFeePercent != null ? pixFeePercent : current.pixFeePercent(),
                pixFeeThreshold != null ? Money.of(pixFeeThreshold) : current.pixFeeThreshold(),
                withdrawalFee != null ? Money.of(withdrawalFee) : current.withdrawalFee(),
                pixMin != null ? Money.of(pixMin) : current.pixMin(),
                pixMax != null ? Money.of(pixMax) : current.pixMax(),
                withdrawalMin != null ? Money.of(withdrawalMin) : current.withdrawalMin(),
                withdrawalMax != null ? Money.of(withdrawalMax) : current.withdrawalMax(),
                rateLimitRps != null ? rateLimitRps : current.rateLimitRps(),
                rateLimitRpm != null ? rateLimitRpm : current.rateLimitRpm(),
                apiBlocked != null ? apiBlocked : current.apiBlocked(),
                honeypot != null ? honeypot : current.honeypot());
    }

    /** Valor canônico do {@code pix_fee_type} (inválido → exceção do domínio). */
    private static MerchantConfig.PixFeeType parsePixFeeType(String value) {
        return MerchantConfig.PixFeeType.valueOf(value.trim().toUpperCase());
    }
}
