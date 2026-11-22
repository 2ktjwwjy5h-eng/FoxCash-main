package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.broadcast.BroadcastLogStatus;

/**
 * Requisição de log por destinatário (Spec 79) — {@code POST
 * /api/v1/foxcash/broadcasts/{id}/logs}. O bot reporta o resultado de UM
 * destinatário por chamada (idempotente por {@code (broadcast_id, recipient_id)}).
 */
public record BroadcastLogRequest(
        String recipientId,
        String status,
        String errorMessage) {

    public BroadcastLogStatus parsedStatus() {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status e obrigatorio no log do broadcast");
        }
        try {
            return BroadcastLogStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status invalido: " + status
                    + " (use SUCCESS, BLOCKED ou FAILED)");
        }
    }
}
