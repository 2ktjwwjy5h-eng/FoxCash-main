package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.port.in.BroadcastManagementUseCase;

import java.time.Instant;
import java.util.UUID;

/**
 * Requisição de criação de broadcast no portal (Spec 79). {@code buttons_json}
 * é o JSON cru {@code {"rows": [...]}} (validado por plataforma na aplicação —
 * D8). {@code scheduled_at} só no modo SCHEDULED; {@code interval_seconds} só
 * no modo REPOST.
 */
public record BroadcastRequest(
        UUID storeBotId,
        String mode,
        String targetChatId,
        String content,
        String mediaUrl,
        String mediaType,
        String mediaFileId,
        String buttonsJson,
        Instant scheduledAt,
        Integer intervalSeconds) {

    public BroadcastManagementUseCase.CreateBroadcastCommand toCommand() {
        return new BroadcastManagementUseCase.CreateBroadcastCommand(
                storeBotId, parsedMode(), targetChatId, content, mediaUrl, mediaType,
                mediaFileId, buttonsJson, scheduledAt, intervalSeconds);
    }

    private BroadcastMode parsedMode() {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("mode e obrigatorio no broadcast");
        }
        try {
            return BroadcastMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode invalido: " + mode);
        }
    }
}
