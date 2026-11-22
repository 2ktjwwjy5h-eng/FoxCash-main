package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta de bot de venda (Spec 74) — o token NUNCA é devolvido: apenas
 * {@code tokenMask} (últimos 4). Usada pelo portal (CRUD do lojista).
 */
public record StoreBotResponse(
        UUID id,
        BotPlatform platform,
        BotStatus status,
        String displayName,
        String username,
        String applicationId,
        String tokenMask,
        String errorMessage,
        Instant lastHealthCheckAt,
        Instant createdAt,
        Instant updatedAt) {

    public static StoreBotResponse from(StoreBot bot) {
        return new StoreBotResponse(
                bot.id(), bot.platform(), bot.status(), bot.displayName(), bot.username(),
                bot.applicationId(), bot.tokenMask(), bot.errorMessage(),
                bot.lastHealthCheckAt(), bot.createdAt(), bot.updatedAt());
    }
}
