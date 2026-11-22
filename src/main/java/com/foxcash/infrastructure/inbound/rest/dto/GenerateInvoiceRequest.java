package com.foxcash.infrastructure.inbound.rest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;

import java.time.YearMonth;

/**
 * Request de geracao de fatura para um periodo (ex.: "2025-08").
 */
public record GenerateInvoiceRequest(
        @NotNull @JsonFormat(pattern = "yyyy-MM") YearMonth period) {
}
