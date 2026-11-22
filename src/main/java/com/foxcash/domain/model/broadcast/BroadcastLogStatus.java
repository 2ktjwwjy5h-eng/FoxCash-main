package com.foxcash.domain.model.broadcast;

/**
 * Resultado do envio por destinatário (Spec 79) — reportado pelo bot via
 * {@code POST /api/v1/foxcash/broadcasts/{id}/logs}, idempotente por
 * {@code (broadcast_id, recipient_id)} (UNIQUE na tabela).
 */
public enum BroadcastLogStatus {
    /** Enviado com sucesso. */
    SUCCESS,
    /** Destinatário bloqueou o bot / sem permissão. */
    BLOCKED,
    /** Falha de envio (erro de plataforma/rede). */
    FAILED,
}
