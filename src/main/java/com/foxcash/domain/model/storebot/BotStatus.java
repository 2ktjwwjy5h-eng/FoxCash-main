package com.foxcash.domain.model.storebot;

/**
 * Ciclo de vida do bot de venda (Spec 74).
 *
 * <p>Transições válidas (máquina de estados no {@link StoreBot}):
 * {@code REGISTERING → ACTIVE}, {@code ACTIVE ⇄ PAUSED},
 * {@code ACTIVE/PAUSED/REGISTERING → ERROR} (falha de integração) e
 * qualquer estado → {@code deleted} (soft-delete). Transição inválida →
 * 409 {@code invalid_bot_state}.
 */
public enum BotStatus {
    /** Registrado no banco, integração ainda não concluída (getMe/setWebhook). */
    REGISTERING,

    /** Integrado e operacional (webhook registrado / conexão ativa). */
    ACTIVE,

    /** Pausado pelo lojista (integração desativada; updates descartados). */
    PAUSED,

    /** Falha de integração/health (webhook não registra, token inválido, conexão cai). */
    ERROR
}
