package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request de geracao de cobranca Pix ({@code POST /api/v1/foxcash/pix/cob}).
 *
 * <p>Gateway somente para o vendedor (decisao de produto 2026-08-04): nenhum
 * dado pessoal do comprador e capturado — o contato com o cliente e do bot do
 * lojista. {@code buyerId} e um pseudonimo OPCIONAL enviado pelo bot (sem PII;
 * apenas o hash SHA-256 e persistido) para triagem antifraude (Spec 19).
 * {@code idempotencyKey} e OBRIGATÓRIA (Spec 28) e unica por lojista.
 */
public record PixChargeRequest(
        @NotNull UUID merchantId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        // Auditoria 2026-08-05 (B-04): teto de 24h (86400s) — mesmo limite do PSP.
        @Min(value = 1) @Max(value = 86_400) Integer expirationSeconds,
        @Size(max = 64) String buyerId,
        @NotBlank @Size(max = 64) String idempotencyKey) {
}
