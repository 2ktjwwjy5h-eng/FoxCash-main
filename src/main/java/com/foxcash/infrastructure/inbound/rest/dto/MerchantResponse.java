package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response de lojista (Spec 39) — nome e e-mail no lugar de
 * tradingName/legalName/documento.
 */
public record MerchantResponse(
        UUID id,
        String name,
        String email,
        MerchantStatus status,
        BigDecimal transactionFee,
        BigDecimal monthlyFee,
        Instant createdAt) {

    public static MerchantResponse from(Merchant merchant) {
        return new MerchantResponse(
                merchant.id(),
                merchant.name(),
                merchant.email(),
                merchant.status(),
                merchant.pricingPlan().transactionFee().amount(),
                merchant.pricingPlan().monthlyFee().amount(),
                merchant.createdAt());
    }
}
