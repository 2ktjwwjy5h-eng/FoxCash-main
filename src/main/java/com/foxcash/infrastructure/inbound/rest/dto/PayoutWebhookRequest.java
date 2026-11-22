package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload do webhook de saida (PIX de saída) do PSP — Spec 36.
 *
 * <p>{@code status} no vocabulario do PSP: {@code "PAID"} (confirmado) ou
 * {@code "FAILED"} (rejeitado — estorno automatico do debito ao saldo).
 */
public record PayoutWebhookRequest(
        @NotBlank String externalReference,
        @NotBlank String status,
        String eventId,
        String failedReason) {
}
