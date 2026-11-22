package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.application.service.StoreBotQueryService;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta do espelho read-only do Master Console (Spec 74) — visão geral com o
 * nome do lojista dono do bot. Sem token (só máscara/últimos 4).
 */
public record MasterBotResponse(
        UUID id,
        UUID merchantId,
        String merchantName,
        String platform,
        String status,
        String displayName,
        String username,
        String applicationId,
        String tokenMask,
        String errorMessage,
        Instant lastHealthCheckAt,
        Instant createdAt,
        Instant updatedAt) {

    public static MasterBotResponse from(StoreBotQueryService.MasterBotView view) {
        var bot = view.bot();
        return new MasterBotResponse(
                bot.id(), bot.merchantId(), view.merchantName(), bot.platform().name(),
                bot.status().name(), bot.displayName(), bot.username(), bot.applicationId(),
                bot.tokenMask(), bot.errorMessage(), bot.lastHealthCheckAt(),
                bot.createdAt(), bot.updatedAt());
    }
}
