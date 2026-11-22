package com.foxcash.domain.model.botfunnel;

/**
 * Parâmetros UTM capturados no início da conversa (Spec 81/D4) — vinculados ao
 * lead (Spec 75). Todos anuláveis; normalizados (máx. 255 chars, sem HTML).
 * "Primeiro valor vence": gravados na CRIAÇÃO do lead; replay nunca sobrescreve.
 */
public record UtmParams(
        String source,
        String medium,
        String campaign,
        String content,
        String term) {

    /** Cria com todos os campos; cada um normalizado (null se vazio/em branco). */
    public static UtmParams of(String source, String medium, String campaign,
                               String content, String term) {
        return new UtmParams(
                normalize(source), normalize(medium), normalize(campaign),
                normalize(content), normalize(term));
    }

    /** True quando ao menos um campo UTM está presente. */
    public boolean hasAny() {
        return source != null || medium != null || campaign != null
                || content != null || term != null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 255) {
            trimmed = trimmed.substring(0, 255);
        }
        // Sanitização básica — sem HTML/markup (o UTM vira payload de provider).
        trimmed = trimmed.replaceAll("<[^>]*>", "").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
