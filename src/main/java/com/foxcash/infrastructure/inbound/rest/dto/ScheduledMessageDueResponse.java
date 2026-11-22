package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageAudience;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resposta da busca de mensagens programadas vencidas pelo bot (Spec 51) —
 * {@code GET /api/v1/foxcash/scheduled-messages?dueBefore=}: a mensagem + os
 * {@code recipients} (platform_user_id dos compradores com entrega entregue da
 * loja — Spec 49, deduplicados; em claro ao dono via API key, criptografados
 * em repouso).
 */
public record ScheduledMessageDueResponse(
        UUID id,
        String text,
        Instant scheduledAt,
        String cron,
        ScheduledMessageAudience audience,
        List<String> recipients) {

    public static ScheduledMessageDueResponse from(ScheduledMessage message, List<String> recipients) {
        return new ScheduledMessageDueResponse(
                message.id(), message.text(), message.scheduledAt(), message.cron(),
                message.audience(), recipients);
    }
}
