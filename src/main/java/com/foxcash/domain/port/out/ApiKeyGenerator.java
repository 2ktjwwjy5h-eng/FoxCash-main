package com.foxcash.domain.port.out;

/**
 * Porta de saida: geracao de API key de lojista (Spec 12).
 *
 * <p>A chave em claro e devolvida uma unica vez (no cadastro); apenas o hash
 * SHA-256 e persistido ({@code merchants.api_key_hash}) para lookup exato —
 * mesmo padrao do {@code document_hash} (ADR-004).
 */
public interface ApiKeyGenerator {

    /**
     * Gera um par (texto claro, hash SHA-256 hex).
     *
     * @param plaintext chave em claro no formato {@code mch_<32 hex>} — nunca persistida
     * @param hash      SHA-256 (hex) da chave — unica forma armazenada
     */
    record ApiKeyPair(String plaintext, String hash) {
    }

    ApiKeyPair generate();
}
