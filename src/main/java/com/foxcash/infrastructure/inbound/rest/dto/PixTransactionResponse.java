package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response de transacao Pix.
 */
public record PixTransactionResponse(
        UUID id,
        UUID merchantId,
        BigDecimal amount,
        String description,
        String pixKey,
        String endToEndId,
        TransactionStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant paidAt) {

    public static PixTransactionResponse from(PixTransaction transaction) {
        return new PixTransactionResponse(
                transaction.id(),
                transaction.merchantId(),
                transaction.amount().amount(),
                transaction.description(),
                transaction.pixKey(),
                transaction.endToEndId(),
                transaction.status(),
                transaction.createdAt(),
                transaction.updatedAt(),
                transaction.paidAt());
    }
}
