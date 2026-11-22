package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.application.service.MasterBroadcastQueryService;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Item da listagem de broadcasts no Master Console (Spec 89) — visão geral com
 * dados do lojista e do bot em cada linha. **Sem {@code content}** na lista
 * (só no detalhe — conteúdo pode ser longo).
 */
public record MasterBroadcastSummary(
        UUID id,
        UUID merchantId,
        String merchantName,
        UUID botId,
        String botDisplayName,
        String botPlatform,
        BroadcastMode mode,
        BroadcastStatus status,
        String targetChatId,
        Instant scheduledAt,
        Instant createdAt) {

    public static MasterBroadcastSummary from(MasterBroadcastQueryService.MasterBroadcastView view) {
        Broadcast b = view.broadcast();
        return new MasterBroadcastSummary(
                b.id(),
                view.bot() == null ? null : view.bot().merchantId(),
                view.merchantName(),
                b.storeBotId(),
                view.bot() == null ? null : view.bot().displayName(),
                view.bot() == null ? null : view.bot().platform().name(),
                b.mode(),
                b.status(),
                b.targetChatId(),
                b.scheduledAt(),
                b.createdAt());
    }
}
