package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.application.service.MasterBroadcastQueryService;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.model.broadcast.BroadcastMediaType;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Detalhe de broadcast no Master Console (Spec 89) — tudo do summary + conteúdo
 * completo (texto, mídia, botões — o que o operador precisa auditar) +
 * estatísticas de envio ({@code success/blocked/failed}) + logs por
 * destinatário. **Sem {@code media_file_id}** (é interno do Telegram — padrão
 * "sem token/segredo do bot em resposta"); sem {@code interval_seconds} (interno
 * do motor REPOST).
 */
public record MasterBroadcastDetail(
        UUID id,
        UUID merchantId,
        String merchantName,
        UUID botId,
        String botDisplayName,
        String botPlatform,
        BroadcastMode mode,
        BroadcastStatus status,
        String targetChatId,
        String content,
        String mediaUrl,
        BroadcastMediaType mediaType,
        String buttonsJson,
        String errorMessage,
        Instant scheduledAt,
        Instant createdAt,
        long successCount,
        long blockedCount,
        long failedCount,
        List<BroadcastLogResponse> logs) {

    public static MasterBroadcastDetail from(MasterBroadcastQueryService.MasterBroadcastView view,
                                             List<BroadcastLog> domainLogs) {
        Broadcast b = view.broadcast();
        MasterBroadcastQueryService.LogCounts counts = view.counts(domainLogs);
        List<BroadcastLogResponse> logs = domainLogs.stream().map(BroadcastLogResponse::from).toList();
        return new MasterBroadcastDetail(
                b.id(),
                view.bot() == null ? null : view.bot().merchantId(),
                view.merchantName(),
                b.storeBotId(),
                view.bot() == null ? null : view.bot().displayName(),
                view.bot() == null ? null : view.bot().platform().name(),
                b.mode(),
                b.status(),
                b.targetChatId(),
                b.content(),
                b.mediaUrl(),
                b.mediaType(),
                b.buttons() == null ? null : b.buttons().json(),
                b.errorMessage(),
                b.scheduledAt(),
                b.createdAt(),
                counts.success(),
                counts.blocked(),
                counts.failed(),
                logs);
    }
}
