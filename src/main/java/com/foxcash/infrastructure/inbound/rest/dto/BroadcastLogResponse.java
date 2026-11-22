package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.model.broadcast.BroadcastLogStatus;

import java.time.Instant;

/**
 * Log por destinatário do broadcast (Spec 79) — resultado do envio reportado
 * pelo bot (SUCCESS/BLOCKED/FAILED + error_message).
 */
public record BroadcastLogResponse(
        String recipientId,
        BroadcastLogStatus status,
        String errorMessage,
        Instant sentAt) {

    public static BroadcastLogResponse from(BroadcastLog log) {
        return new BroadcastLogResponse(log.recipientId(), log.status(),
                log.errorMessage(), log.sentAt());
    }
}
