package com.foxcash.domain.port.in;

import com.foxcash.domain.model.store.ScheduledMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: consulta das mensagens programadas da loja (Spec 51) —
 * API do lojista/bot (API key, {@code ROLE_MERCHANT}). O bot busca as vencidas
 * ({@code dueBefore}) com os {@code recipients} (platform_user_id dos
 * compradores com entrega entregue da loja — Spec 49, deduplicados) e confirma
 * o envio ({@code markSent} → SENT, idempotente).
 */
public interface ScheduledMessageQueryUseCase {

    /** Vencidas (SCHEDULED e scheduledAt ≤ dueBefore, ou cron com próximo disparo ≤ dueBefore). */
    List<DueScheduledMessage> due(UUID merchantId, Instant dueBefore);

    /** 204 — marca como enviada (idempotente); 404 {@code message_not_found} cross-loja. */
    void markSent(UUID merchantId, UUID scheduledMessageId);

    /** Mensagem vencida + destinatários (em claro ao dono; criptografados em repouso). */
    record DueScheduledMessage(ScheduledMessage message, List<String> recipients) {
    }
}
