package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.merchant.MerchantConfig;

import java.math.BigDecimal;

/**
 * Config fina por lojista (Spec 98 — D5). Valores {@code null} = sem override
 * (usa o default global — decisão registrada: a resposta espelha o estado
 * armazenado para o round-trip do PUT parcial; os valores EFETIVOS são os que
 * os gates de runtime aplicam e o painel (Spec 97) já expõe).
 */
public record MerchantConfigResponse(
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
        boolean apiBlocked,
        boolean honeypot) {

    public static MerchantConfigResponse from(MerchantConfig config) {
        return new MerchantConfigResponse(
                config.pixFeeType() == null ? null : config.pixFeeType().name(),
                config.pixFeeFixed() == null ? null : config.pixFeeFixed().amount(),
                config.pixFeePercent(),
                config.pixFeeThreshold() == null ? null : config.pixFeeThreshold().amount(),
                config.withdrawalFee() == null ? null : config.withdrawalFee().amount(),
                config.pixMin() == null ? null : config.pixMin().amount(),
                config.pixMax() == null ? null : config.pixMax().amount(),
                config.withdrawalMin() == null ? null : config.withdrawalMin().amount(),
                config.withdrawalMax() == null ? null : config.withdrawalMax().amount(),
                config.rateLimitRps(),
                config.rateLimitRpm(),
                config.apiBlocked(),
                config.honeypot());
    }
}
