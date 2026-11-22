package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request de cobranca Gift PIX (Spec 23) — {@code presetId} (valor predefinido)
 * OU {@code amount} (personalizado). Exatamente um dos dois deve vir preenchido.
 *
 * <p>Auditoria 2026-08-05 (M2): limites de tamanho alinhados as colunas do
 * banco — 400 em vez de 500. {@code idempotencyKey} e OBRIGATÓRIA (Spec 28).
 */
public record PortalGiftChargeRequest(
        UUID presetId,
        @Digits(integer = 13, fraction = 2) BigDecimal amount,
        // Auditoria 2026-08-05 (B-04): teto de 24h (86400s) — mesmo limite do PSP.
        @Max(value = 86_400) Integer expirationSeconds,
        @Size(max = 64) String buyerId,
        @Size(max = 255) String description,
        @NotBlank @Size(max = 64) String idempotencyKey) {
}
