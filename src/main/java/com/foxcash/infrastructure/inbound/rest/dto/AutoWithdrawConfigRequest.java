package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Configuracao de saque automatico (Spec 70) — {@code PUT /api/v1/portal/auto-withdraw}.
 *
 * <p>Shape minima: {@code enabled} e {@code threshold} obrigatorios
 * ({@code validation_error} 400). As regras de dominio ({@code threshold > 0},
 * {@code >= min-threshold}, {@code Money} M9, destino 400/404/409) ficam no
 * servico — todas respondem {@code invalid_argument} (400), fiel ao requisito.
 */
public record AutoWithdrawConfigRequest(
        @NotNull Boolean enabled,
        BigDecimal threshold,
        UUID destinationId) {
}
