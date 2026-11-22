package com.foxcash.domain.port.in;

import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta de entrada: gestão de broadcasts pelo portal (Spec 79),
 * {@code ROLE_MERCHANT_PORTAL}. Tudo por {@code merchantId} do principal —
 * nunca por id solto (404 {@code broadcast_not_found} cross-lojista, padrão
 * IDOR das Specs 24/31). O disparo NUNCA é síncrono: criar/agendar retorna
 * imediatamente (o bot busca via API).
 */
public interface BroadcastManagementUseCase {

    /**
     * Cria o broadcast (valida modo/destino/conteúdo/mídia/botões; auditoria
     * BROADCAST_CREATED/SCHEDULED na mesma transação). Resposta imediata — o
     * bot (Spec 52) busca pendências via API.
     */
    Broadcast create(UUID merchantId, CreateBroadcastCommand command);

    /**
     * Cancela (DELETE físico, D3) um broadcast {@code PENDING} (inclui
     * SCHEDULED/REPOST aguardando) — auditoria BROADCAST_CANCELLED. PROCESSING
     * → 409 {@code invalid_broadcast_state}.
     */
    void cancel(UUID merchantId, UUID broadcastId);

    /** Comando de criação (modo/destino/conteúdo/mídia/botões/agendamento). */
    record CreateBroadcastCommand(
            UUID storeBotId,
            BroadcastMode mode,
            String targetChatId,
            String content,
            String mediaUrl,
            String mediaType,
            String mediaFileId,
            String buttonsJson,
            Instant scheduledAt,
            Integer intervalSeconds) {
    }
}
