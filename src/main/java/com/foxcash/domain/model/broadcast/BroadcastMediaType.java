package com.foxcash.domain.model.broadcast;

/**
 * Tipo da mídia anexada ao broadcast (Spec 79) — coluna {@code broadcasts.media_type}
 * (CHECK 'image'/'video'/'audio'). O envio reusa o {@code media_file_id} do
 * Telegram (canal CDN) ou a {@code media_url} (Discord/fallback) — sem
 * re-upload por destinatário.
 */
public enum BroadcastMediaType {
    IMAGE,
    VIDEO,
    AUDIO
}
