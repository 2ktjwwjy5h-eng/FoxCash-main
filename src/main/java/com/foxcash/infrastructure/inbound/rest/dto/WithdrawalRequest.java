package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request de saque do lojista (Spec 14, Spec 32) — valor a debitar do wallet e
 * o DESTINO cadastrado na conta do lojista (obrigatório; 400 sem ele).
 */
public record WithdrawalRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull UUID destinationId) {
}
