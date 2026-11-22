package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transacao do lojista vista pelo master (Spec 97, D3.3) — dados de venda
 * enriquecidos: nome do comprador via lead do CRM ({@code buyerName}; sem lead
 * → {@code null} e a UI usa {@code buyerIdHash} opaco), produto/servico via
 * plano ({@code productName}) e {@code netAmount = amount − fee}. Sem
 * {@code pix_key}/{@code pix_qr_code} em claro (payload Pix so no detalhe
 * PENDING do contrato Spec 25).
 */
public record MerchantPanelTransaction(
        UUID transactionId,
        BigDecimal amount,
        TransactionStatus status,
        String buyerName,
        String buyerIdHash,
        String productName,
        String externalReference,
        BigDecimal fee,
        BigDecimal netAmount,
        Instant createdAt) {
}
