package com.foxcash.domain.model.storebot;

/**
 * Plataforma do bot de venda (Spec 74) — Telegram ou Discord. O renderizador
 * (Spec 52, bots Node.js stateless) é o mesmo; o registro central só gerencia
 * a identidade e o ciclo de vida do bot no PostgreSQL.
 */
public enum BotPlatform {
    TELEGRAM,
    DISCORD
}
