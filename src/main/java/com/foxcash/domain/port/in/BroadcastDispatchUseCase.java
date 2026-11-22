package com.foxcash.domain.port.in;

import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastLogStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: dispatch dos broadcasts pelo bot (Spec 79) — API key do
 * lojista (principal = {@code merchantId}), padrão Spec 51. O bot busca as
 * pendências ({@code due}), envia com rate limit nativo e reporta o resultado
 * por destinatário ({@code reportLog}); o backend conclui via {@code markSent}.
 * O modo WELCOME é resolvido no evento de entrada ({@code welcomeTrigger}).
 */
public interface BroadcastDispatchUseCase {

    /**
     * Pendências do lojista (pull): {@code PENDING} com {@code scheduled_at <=
     * dueBefore} OU {@code NOW} sem agendamento. Marca {@code PROCESSING} (o
     * bot pegou). WELCOME nunca vem aqui.
     */
    List<Broadcast> due(UUID merchantId, Instant dueBefore);

    /**
     * Confirma o envio: {@code COMPLETED} se ≥1 {@code SUCCESS} no log;
     * {@code FAILED} se nenhum (logs individuais sempre preservados).
     */
    void markSent(UUID merchantId, UUID broadcastId);

    /**
     * Log por destinatário (idempotente por {@code (broadcast_id, recipient_id)}
     * — UNIQUE; replay não duplica). Uma falha por destinatário nunca derruba os
     * demais.
     */
    void reportLog(UUID merchantId, UUID broadcastId, String recipientId,
                   BroadcastLogStatus status, String errorMessage);

    /**
     * Evento de entrada de membro (WELCOME): devolve a mensagem de boas-vindas
     * ativa do bot para aquele chat (mais recente {@code PENDING} com
     * {@code target_chat_id}); idempotente por {@code (chatId, userId)} — evento
     * duplicado não envia 2×. Sem mensagem → {@code null} (no-op 204).
     */
    WelcomeResult welcomeTrigger(UUID merchantId, UUID botId, String chatId, String userId);

    /** Mensagem WELCOME resolvida no evento de entrada (D6). */
    record WelcomeResult(UUID broadcastId, String content, String mediaUrl,
                         String mediaType, String mediaFileId, String buttonsJson) {
    }
}
