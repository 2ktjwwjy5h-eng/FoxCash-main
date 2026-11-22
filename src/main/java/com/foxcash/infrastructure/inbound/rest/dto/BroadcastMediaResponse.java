package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.broadcast.BroadcastMediaType;

/**
 * Resposta do upload de mídia do broadcast (Spec 79/D7) — devolvida ao editor
 * (Spec 80): Telegram devolve {@code media_file_id} (file_id da CDN — reuso nos
 * disparos sem re-upload); Discord devolve {@code media_url} da CDN.
 */
public record BroadcastMediaResponse(
        String mediaUrl,
        String mediaFileId,
        BroadcastMediaType mediaType) {
}
