package com.foxcash.domain.port.out;

/**
 * Porta de saída: geração/hash do token opaco de sessão do master (Spec 13).
 * O token em claro é devolvido no login; apenas o hash SHA-256 é persistido.
 */
public interface SessionTokenGenerator {

    record TokenPair(String plaintext, String hash) {
    }

    TokenPair generate();

    /** Hash SHA-256 (hex) de um token apresentado (usado no logout/lookup). */
    String hash(String rawToken);
}
