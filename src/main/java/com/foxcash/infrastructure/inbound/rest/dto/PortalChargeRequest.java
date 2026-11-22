package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request de cobranca avulsa do portal (Spec 23) — valor, descricao, expiracao
 * e buyerId opcional (pseudonimo; apenas o hash SHA-256 e persistido, Spec 19).
 * O merchantId vem do principal da sessao (ROLE_MERCHANT_PORTAL).
 *
 * <p>Auditoria 2026-08-05 (M2): limites de tamanho alinhados as colunas do
 * banco (descricao VARCHAR(255), buyerId hash VARCHAR(64)) — 400 em vez de 500.
 * {@code idempotencyKey} e OBRIGATÓRIA (Spec 28) e unica por lojista.
 */
public record PortalChargeRequest(
        @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description,
        // Auditoria 2026-08-05 (B-04): teto de 24h (86400s) — mesmo limite do PSP.
        @Max(value = 86_400) Integer expirationSeconds,
        @Size(max = 64) String buyerId,
        @NotBlank @Size(max = 64) String idempotencyKey) {
}
