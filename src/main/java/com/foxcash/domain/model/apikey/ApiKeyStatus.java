package com.foxcash.domain.model.apikey;

/**
 * Status de uma chave de API de lojista (Spec 24). Uma chave revogada deixa
 * de autenticar (401 em {@code X-Api-Key}).
 */
public enum ApiKeyStatus {

    /** Chave válida — autentica enquanto o lojista estiver ACTIVE. */
    ACTIVE,

    /** Chave revogada pelo lojista (portal) — não autentica mais. */
    REVOKED
}
