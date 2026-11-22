package com.foxcash.domain.model.botfunnel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento do funil por bot (Spec 75) — trilha append-only
 * {@code lead → checkout_started → paid} por {@code (bot_id, platform_user_id)}.
 * O {@code transaction_id} é preenchido para {@code checkout_started}/{@code paid}
 * (NULL para {@code lead}). A métrica de venda NUNCA soma estes eventos como
 * dinheiro — lê o status da transação (fonte oficial).
 */
public record BotFunnelEvent(
        UUID botId,
        String platformUserId,
        FunnelEvent event,
        UUID transactionId,
        Instant occurredAt) {

    public BotFunnelEvent {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(platformUserId, "platformUserId must not be null");
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (platformUserId.isBlank()) {
            throw new IllegalArgumentException("platformUserId nao pode ser vazio");
        }
    }

    /** Cria um evento de funil (lead/checkout_started/paid). */
    public static BotFunnelEvent create(UUID botId, String platformUserId, FunnelEvent event,
                                        UUID transactionId, Instant now) {
        return new BotFunnelEvent(botId, platformUserId, event, transactionId, now);
    }

    /** Restaura do banco (o dado persistido é confiável). */
    public static BotFunnelEvent restore(UUID botId, String platformUserId, FunnelEvent event,
                                         UUID transactionId, Instant occurredAt) {
        return new BotFunnelEvent(botId, platformUserId, event, transactionId, occurredAt);
    }
}
