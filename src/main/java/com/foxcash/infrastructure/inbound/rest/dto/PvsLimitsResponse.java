package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.PvsPaymentUseCase;

import java.math.BigDecimal;

/**
 * Response dos limites do Pagamento via Saldo (Spec 120) — teto diário,
 * valor já usado hoje (soma de {@code amount+fee} de TODOS os PVS criados no
 * dia), restante (cap − used, floor 0) e teto por operação.
 */
public record PvsLimitsResponse(
        BigDecimal dailyCap,
        BigDecimal usedToday,
        BigDecimal remaining,
        BigDecimal maxPerPayment) {

    public static PvsLimitsResponse from(PvsPaymentUseCase.Limits limits) {
        return new PvsLimitsResponse(
                limits.dailyCap().amount(),
                limits.usedToday().amount(),
                limits.remaining().amount(),
                limits.maxPerPayment().amount());
    }
}
