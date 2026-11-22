package com.foxcash.domain.model.botfunnel;

/**
 * Eventos do funil por bot (Spec 75) — {@code lead → checkout_started → paid}
 * por {@code (bot_id, platform_user_id)}. O funil é trilha append-only: a
 * métrica de venda lê o status da transação (fonte oficial — {@code PAID}
 * não estornado), nunca o evento {@code paid} como dinheiro.
 */
public enum FunnelEvent {

    /** Usuário iniciou conversa com o bot (Telegram: update; Discord: interação). */
    LEAD,

    /** Usuário abriu o checkout de um plano (purchase com {@code botId}). */
    CHECKOUT_STARTED,

    /** Transação {@code PAID} do bot (webhook do PSP — mesma transação). */
    PAID
}
