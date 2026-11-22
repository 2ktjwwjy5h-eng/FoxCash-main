package com.foxcash.domain.port.out;

/**
 * Porta de saída: geração/hash do token one-time de recuperação de senha
 * (Spec 40). O token em claro é devolvido no {@code forgot-password} (só em
 * dev); apenas o hash SHA-256 é persistido em {@code password_reset_tokens}.
 * Mesmo padrão do {@link SessionTokenGenerator} (Spec 13/20), prefixo {@code rst_}.
 */
public interface ResetTokenGenerator {

    record TokenPair(String plaintext, String hash) {
    }

    TokenPair generate();

    /** Hash SHA-256 (hex) de um token apresentado (usado no reset-password). */
    String hash(String rawToken);
}
