package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.storebot.BotPlatform;

/**
 * Request de registro de bot de venda (Spec 74) — {@code platform}
 * (telegram/discord), {@code token} (do BotFather/Dev Portal; obrigatório),
 * {@code displayName} (obrigatório) e {@code applicationId} (Discord, opcional).
 * O token nunca é devolvido em resposta (só máscara/últimos 4).
 */
public record StoreBotRequest(
        String platform,
        String token,
        String displayName,
        String applicationId) {

    /** Plataforma normalizada (case-insensitive) ou null se ausente. */
    public BotPlatform parsedPlatform() {
        if (platform == null || platform.isBlank()) {
            return null;
        }
        try {
            return BotPlatform.valueOf(platform.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("platform invalida: " + platform);
        }
    }
}
