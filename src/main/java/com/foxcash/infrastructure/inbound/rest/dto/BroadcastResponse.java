package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMediaType;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta de broadcast no portal (Spec 79) — sem o conteúdo em claro na
 * listagem (apenas metadados); o detalhe usa {@link BroadcastDetailResponse}
 * (com content + logs).
 */
public record BroadcastResponse(
        UUID id,
        UUID storeBotId,
        BroadcastMode mode,
        BroadcastStatus status,
        String targetChatId,
        BroadcastMediaType mediaType,
        String errorMessage,
        Instant scheduledAt,
        Integer intervalSeconds,
        Instant createdAt,
        Instant updatedAt) {

    public static BroadcastResponse from(Broadcast broadcast) {
        return new BroadcastResponse(
                broadcast.id(), broadcast.storeBotId(), broadcast.mode(), broadcast.status(),
                broadcast.targetChatId(), broadcast.mediaType(), broadcast.errorMessage(),
                broadcast.scheduledAt(), broadcast.intervalSeconds(),
                broadcast.createdAt(), broadcast.updatedAt());
    }
}
