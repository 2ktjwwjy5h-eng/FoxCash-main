package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.infrastructure.inbound.rest.WithdrawalDestinationMasker;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta de destino de saque (Spec 31) — o valor em claro NUNCA é devolvido;
 * apenas a máscara ({@code mask}) + metadados.
 */
public record WithdrawalDestinationResponse(
        UUID id,
        String type,
        String label,
        String mask,
        String status,
        Instant createdAt) {

    public static WithdrawalDestinationResponse from(WithdrawalDestination destination) {
        return new WithdrawalDestinationResponse(
                destination.id(),
                destination.type().name(),
                destination.label(),
                WithdrawalDestinationMasker.mask(destination),
                destination.active() ? "ACTIVE" : "INACTIVE",
                destination.createdAt());
    }
}
