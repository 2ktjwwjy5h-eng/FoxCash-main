package com.foxcash.domain.model.store;

/**
 * Público-alvo de uma mensagem programada (Spec 51). Nesta versão apenas
 * {@code BUYERS} — o backend resolve os destinatários (os
 * {@code platform_user_id} dos compradores com entrega entregue da loja, Spec
 * 49). {@code ALL} (enviar a todos os usuários da plataforma) fica como
 * evolução, pois exigiria uma lista de usuários fora do backend (os bots são
 * stateless).
 */
public enum ScheduledMessageAudience {

    BUYERS;

    /** Parse de request: null/blank = {@code BUYERS} (default); inválido → 400. */
    public static ScheduledMessageAudience parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return BUYERS;
        }
        try {
            return ScheduledMessageAudience.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("audience invalida (nesta versao apenas BUYERS): " + raw);
        }
    }
}
