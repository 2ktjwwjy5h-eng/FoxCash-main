package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalStatus;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.infrastructure.inbound.rest.WithdrawalDestinationMasker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response de saida (saque) — usado pelo lojista e pelo master. A partir da
 * Spec 32 expõe o {@code type} (MERCHANT | ADMINISTRATIVE), o {@code
 * destination} SEMPRE mascarado (nunca a chave em claro — Spec 31) e o
 * {@code walletBalance} pós-débito (preenchido apenas na resposta do POST —
 * critério de aceite da Spec 32; listagens não carregam o saldo). A partir da
 * Spec 36 expõe o estado real ({@code PROCESSING/COMPLETED/FAILED}), a
 * {@code externalReference} do PSP (para acompanhar via webhook/reconciliação)
 * e o {@code failureReason}/{@code failedAt} quando rejeitado.
 */
public record WithdrawalResponse(
        UUID id,
        UUID merchantId,
        BigDecimal amount,
        BigDecimal fee,
        WithdrawalStatus status,
        WithdrawalType type,
        String destination,
        BigDecimal walletBalance,
        String externalReference,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        Instant rejectedAt,
        Instant failedAt) {

    public static WithdrawalResponse from(Withdrawal withdrawal) {
        return from(withdrawal, null);
    }

    public static WithdrawalResponse from(Withdrawal withdrawal, Money walletBalance) {
        return new WithdrawalResponse(
                withdrawal.id(),
                withdrawal.merchantId(),
                withdrawal.amount().amount(),
                withdrawal.fee().amount(),
                withdrawal.status(),
                withdrawal.type(),
                WithdrawalDestinationMasker.maskSnapshot(withdrawal.destinationKeySnapshot()),
                walletBalance == null ? null : walletBalance.amount(),
                withdrawal.externalReference(),
                withdrawal.failureReason(),
                withdrawal.createdAt(),
                withdrawal.completedAt(),
                withdrawal.rejectedAt(),
                withdrawal.failedAt());
    }
}
