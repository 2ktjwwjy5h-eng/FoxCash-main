package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response de cobranca Pix criada.
 */
public record PixChargeResponse(
        UUID transactionId,
        String externalReference,
        String pixQrCode,
        String pixCopyPaste,
        TransactionStatus status,
        Instant createdAt) {

    public static PixChargeResponse from(PixTransaction transaction) {
        return new PixChargeResponse(
                transaction.id(),
                transaction.externalReference(),
                transaction.pixQrCode(),
                transaction.pixCopyPaste(),
                transaction.status(),
                transaction.createdAt());
    }
}
