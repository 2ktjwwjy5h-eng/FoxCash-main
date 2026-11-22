package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.ReceivableWriteOffUseCase;

import java.math.BigDecimal;

/**
 * Response da baixa (write-off) de receivable (Spec 34) — {@code {loss}}.
 */
public record WriteOffResponse(BigDecimal loss) {

    public static WriteOffResponse from(ReceivableWriteOffUseCase.MoneyOutcome outcome) {
        return new WriteOffResponse(outcome.loss().amount());
    }
}
