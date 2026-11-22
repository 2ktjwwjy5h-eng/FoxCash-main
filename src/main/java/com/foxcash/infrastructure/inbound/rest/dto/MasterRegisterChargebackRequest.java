package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request de registro de chargeback pelo master (Spec 19) — notificacao do
 * PSP/operador.
 */
public record MasterRegisterChargebackRequest(
        @NotNull UUID merchantId,
        @NotNull UUID transactionId,
        @Size(max = 255) String reason) {
}
