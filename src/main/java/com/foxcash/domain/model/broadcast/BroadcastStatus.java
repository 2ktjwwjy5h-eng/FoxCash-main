package com.foxcash.domain.model.broadcast;

/**
 * Ciclo de vida do broadcast (Spec 79): {@code PENDING → PROCESSING →
 * COMPLETED/FAILED}. O bot marca {@code PROCESSING} ao buscar pendências
 * (padrão Spec 51) e conclui via {@code markSent} ou logs por destinatário.
 */
public enum BroadcastStatus {
    /** Aguardando o bot buscar (modo NOW/SCHEDULED/REPOST agendado). */
    PENDING,
    /** O bot pegou a pendência e está enviando. */
    PROCESSING,
    /** Envio concluído (≥1 SUCCESS no log). */
    COMPLETED,
    /** Falhou (nenhum SUCCESS) ou cancelado. */
    FAILED,
}
