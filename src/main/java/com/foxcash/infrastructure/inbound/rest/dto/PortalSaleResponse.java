package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.MerchantSalesUseCase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Item da listagem de vendas do portal (Spec 25). {@code buyerIdHash} é o
 * identificador opaco do pagador (Spec 19) — nunca PII; {@code null} quando o
 * bot não enviou. {@code method} é sempre {@code "PIX"}.
 */
public record PortalSaleResponse(
        UUID transactionId,
        String buyerIdHash,
        String method,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        TransactionStatus status,
        Instant createdAt) {

    public static PortalSaleResponse from(MerchantSalesUseCase.Sale sale) {
        return new PortalSaleResponse(
                sale.transactionId(), sale.buyerIdHash(), "PIX",
                sale.grossAmount().amount(), sale.netAmount().amount(),
                sale.status(), sale.createdAt());
    }
}
