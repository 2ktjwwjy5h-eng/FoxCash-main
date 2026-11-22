package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request de registro de chargeback pelo lojista/bot (Spec 19) — disputa
 * reportada pelo comprador. O {@code merchantId} vem da rota
 * ({@code POST /api/v1/merchants/{merchantId}/chargebacks}).
 */
public record MerchantRegisterChargebackRequest(
        @NotNull UUID transactionId,
        @Size(max = 255) String reason) {
}
