package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Payload de webhook do provedor Pix (PSP).
 *
 * <p>O status chega em vocabulario do PSP (ex.: "PAID", "REFUNDED"); o
 * servico de aplicacao normaliza para o dominio ({@code PAID -> APPROVED}).
 */
public record PixWebhookRequest(
        @NotBlank String externalReference,
        @NotBlank String status,
        String eventId,
        Instant settledAt,
        BigDecimal amount) {
}
