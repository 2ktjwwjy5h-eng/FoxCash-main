package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.PvsPaymentUseCase;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response do Pagamento via Saldo (Spec 120) — saque {@code BILL_PAYMENT}
 * criado como {@code PROCESSING} (débito + taxa aplicados, PSP chamado fora da
 * transação). O desfecho (COMPLETED/FAILED) vem pelo webhook de saída/
 * reconciliação e aparece no histórico de saques do portal.
 */
public record PvsPayResponse(
        UUID withdrawalId,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal walletBalance,
        String status) {

    public static PvsPayResponse from(PvsPaymentUseCase.Result result) {
        return new PvsPayResponse(
                result.withdrawalId(),
                result.amount().amount(),
                result.fee().amount(),
                result.walletBalance().amount(),
                result.status());
    }
}
