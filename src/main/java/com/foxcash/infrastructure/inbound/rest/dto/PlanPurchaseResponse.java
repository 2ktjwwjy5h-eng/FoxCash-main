package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response da compra de plano (Spec 49) — mesmo contrato do
 * {@code /foxcash/pix/cob} + {@code planId}.
 */
public record PlanPurchaseResponse(
        UUID transactionId,
        UUID planId,
        String externalReference,
        String pixQrCode,
        String pixCopyPaste,
        TransactionStatus status,
        Instant createdAt) {

    public static PlanPurchaseResponse from(PixTransaction transaction) {
        return new PlanPurchaseResponse(
                transaction.id(),
                transaction.planId(),
                transaction.externalReference(),
                transaction.pixQrCode(),
                transaction.pixCopyPaste(),
                transaction.status(),
                transaction.createdAt());
    }
}
