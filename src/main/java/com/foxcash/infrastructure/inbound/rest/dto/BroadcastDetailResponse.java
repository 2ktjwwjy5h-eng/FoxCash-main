package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMediaType;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Detalhe de broadcast no portal (Spec 79) — inclui o conteúdo (apenas no
 * detalhe, para edição/preview) e os logs por destinatário.
 */
public record BroadcastDetailResponse(
        UUID id,
        UUID storeBotId,
        BroadcastMode mode,
        BroadcastStatus status,
        String targetChatId,
        String content,
        String mediaUrl,
        BroadcastMediaType mediaType,
        String mediaFileId,
        String buttonsJson,
        String errorMessage,
        Instant scheduledAt,
        Integer intervalSeconds,
        Instant createdAt,
        Instant updatedAt,
        List<BroadcastLogResponse> logs) {

    public static BroadcastDetailResponse from(Broadcast broadcast,
                                               List<BroadcastLogResponse> logs) {
        return new BroadcastDetailResponse(
                broadcast.id(), broadcast.storeBotId(), broadcast.mode(), broadcast.status(),
                broadcast.targetChatId(), broadcast.content(), broadcast.mediaUrl(),
                broadcast.mediaType(), broadcast.mediaFileId(),
                broadcast.buttons() == null ? null : broadcast.buttons().json(),
                broadcast.errorMessage(), broadcast.scheduledAt(), broadcast.intervalSeconds(),
                broadcast.createdAt(), broadcast.updatedAt(), logs);
    }
}
