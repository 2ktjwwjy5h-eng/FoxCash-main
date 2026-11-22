package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request de criacao de transacao Pix.
 *
 * <p>{@code buyerId} e o pseudonimo opaco do comprador enviado pelo bot do
 * lojista (opcional; SEM PII — apenas o hash SHA-256 e persistido, Spec 19).
 * {@code idempotencyKey} e OBRIGATÓRIA (Spec 28) e unica por lojista.
 */
public record CreatePixTransactionRequest(
        @NotNull UUID merchantId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @Size(max = 255) String description,
        @NotBlank @Size(max = 255) String pixKey,
        @Size(max = 64) String buyerId,
        @NotBlank @Size(max = 64) String idempotencyKey) {
}
