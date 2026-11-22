package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantDashboardUseCase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Venda recente no dashboard do lojista (Spec 21). {@code buyerIdHash} e o
 * identificador opaco do pagador (Spec 19) — nunca PII.
 */
public record PortalRecentSaleResponse(
        UUID transactionId,
        BigDecimal amount,
        String status,
        Instant createdAt,
        String buyerIdHash) {

    public static PortalRecentSaleResponse from(MerchantDashboardUseCase.RecentSale sale) {
        return new PortalRecentSaleResponse(
                sale.transactionId(), sale.amount().amount(), sale.status(), sale.createdAt(), sale.buyerIdHash());
    }
}
