package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.InvoiceCollectionUseCase;

import java.math.BigDecimal;

/**
 * Response da cobranca manual de fatura (Spec 33) — {@code {debit, remaining}}.
 */
public record CollectionResponse(BigDecimal debit, BigDecimal remaining) {

    public static CollectionResponse from(InvoiceCollectionUseCase.CollectionResult result) {
        return new CollectionResponse(result.debit().amount(), result.remaining().amount());
    }
}
