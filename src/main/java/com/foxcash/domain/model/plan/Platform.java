package com.foxcash.domain.model.plan;

/**
 * Plataforma do comprador (Spec 49) — onde a entrega automática acontece
 * (Discord/Telegram). Persistido na transação e na entrega (única fonte de
 * verdade no backend; bots stateless).
 */
public enum Platform {

    DISCORD,
    TELEGRAM;

    /** Parse de request: aceita maiúsculas/minúsculas; inválido → 400. */
    public static Platform parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("platform e obrigatoria (DISCORD|TELEGRAM)");
        }
        try {
            return Platform.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("platform invalida (DISCORD|TELEGRAM): " + raw);
        }
    }
}
