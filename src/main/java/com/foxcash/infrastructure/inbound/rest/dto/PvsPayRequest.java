package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request do Pagamento via Saldo (Spec 120) — o lojista cola o Pix Copia e Cola
 * ESTÁTICO ({@code pixCode}) e, se o brcode não carrega valor (campo 54), envia
 * o {@code amount} (obrigatório nesse caso; quando o EMV carrega valor, o
 * {@code amount} precisa BATER — 400 {@code pvs_amount_mismatch}).
 *
 * <p>{@code merchantId} vem do principal (ROLE_MERCHANT_PORTAL) — nunca do body.
 */
public record PvsPayRequest(
        @NotBlank(message = "pixCode é obrigatório")
        @Size(max = 1024, message = "pixCode deve ter no máximo 1024 caracteres")
        String pixCode,

        @DecimalMin(value = "0.01", message = "amount deve ser maior que zero")
        BigDecimal amount) {
}
