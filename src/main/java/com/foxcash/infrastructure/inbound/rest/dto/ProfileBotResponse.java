package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;

import java.util.UUID;

/**
 * Bot da frota de um perfil de operação (Spec 122 — GET /portal/profile). Dados
 * leves para o seletor da UI (id/displayName/platform/status — sem token).
 */
public record ProfileBotResponse(
        UUID id,
        String displayName,
        BotPlatform platform,
        BotStatus status) {

    public static ProfileBotResponse from(StoreBot bot) {
        return new ProfileBotResponse(bot.id(), bot.displayName(), bot.platform(), bot.status());
    }
}
