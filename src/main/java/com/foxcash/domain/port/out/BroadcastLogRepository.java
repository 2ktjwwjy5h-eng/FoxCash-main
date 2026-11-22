package com.foxcash.domain.port.out;

import com.foxcash.domain.model.broadcast.BroadcastLog;
import com.foxcash.domain.model.broadcast.BroadcastLogStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência dos logs por destinatário do broadcast (Spec 79).
 * A idempotência por {@code (broadcast_id, recipient_id)} usa o lookup
 * {@code findByBroadcastIdAndRecipientId} (UNIQUE na tabela — replay não
 * duplica).
 */
public interface BroadcastLogRepository {

    BroadcastLog save(BroadcastLog log);

    /** Lookup de idempotência — replay de log não duplica. */
    Optional<BroadcastLog> findByBroadcastIdAndRecipientId(UUID broadcastId, String recipientId);

    /** Histórico do broadcast (portal — detalhe + logs). */
    List<BroadcastLog> findByBroadcastId(UUID broadcastId);

    /** Quantos destinatários tiveram sucesso (decisão COMPLETED vs FAILED). */
    long countByBroadcastIdAndStatus(UUID broadcastId, BroadcastLogStatus status);
}
