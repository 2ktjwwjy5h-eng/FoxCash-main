package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMediaType;
import com.foxcash.domain.model.broadcast.BroadcastMode;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta da busca de broadcasts pendentes pelo bot (Spec 79) —
 * {@code GET /api/v1/foxcash/broadcasts/due?dueBefore=}: o conteúdo rico (tags
 * {primeiro_nome} etc. resolvidas pelo renderTemplate do bot — Spec 52), mídia
 * (file_id/URL sem re-upload) e botões inline (buttons_json cru).
 */
public record BroadcastDueResponse(
        UUID id,
        UUID storeBotId,
        BroadcastMode mode,
        String targetChatId,
        String content,
        String mediaUrl,
        BroadcastMediaType mediaType,
        String mediaFileId,
        String buttonsJson) {

    public static BroadcastDueResponse from(Broadcast broadcast) {
        return new BroadcastDueResponse(
                broadcast.id(), broadcast.storeBotId(), broadcast.mode(),
                broadcast.targetChatId(), broadcast.content(), broadcast.mediaUrl(),
                broadcast.mediaType(), broadcast.mediaFileId(),
                broadcast.buttons() == null ? null : broadcast.buttons().json());
    }
}
