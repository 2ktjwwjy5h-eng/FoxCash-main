package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.MerchantSalesUseCase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Detalhe de uma venda no portal (Spec 25). O payload Pix ({@code description},
 * {@code pixKey}, {@code pixQrCode}, {@code pixCopyPaste}, {@code endToEndId})
 * é preenchido somente quando {@code status = PENDING} (requisito); nos demais
 * status os campos ficam nulos.
 */
public record PortalSaleDetailResponse(
        UUID transactionId,
        String buyerIdHash,
        String method,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        TransactionStatus status,
        Instant createdAt,
        Instant paidAt,
        String description,
        String pixKey,
        String pixQrCode,
        String pixCopyPaste,
        String endToEndId) {

    public static PortalSaleDetailResponse from(MerchantSalesUseCase.SaleDetail detail) {
        return new PortalSaleDetailResponse(
                detail.transactionId(), detail.buyerIdHash(), "PIX",
                detail.grossAmount().amount(), detail.netAmount().amount(),
                detail.status(), detail.createdAt(), detail.paidAt(),
                detail.description(), detail.pixKey(), detail.pixQrCode(),
                detail.pixCopyPaste(), detail.endToEndId());
    }
}
