package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.ResetTokenGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Implementacao do port {@link ResetTokenGenerator} (Spec 40).
 *
 * <p>Gera token one-time no formato {@code rst_<32 hex>} (SecureRandom) e
 * calcula o hash SHA-256 (hex) — apenas o hash e persistido em
 * {@code password_reset_tokens.token_hash}; o texto claro e devolvido no
 * {@code forgot-password} (so em dev).
 */
@Component
public class ResetTokenGeneratorImpl implements ResetTokenGenerator {

    private static final String PREFIX = "rst_";
    private static final int RANDOM_HEX_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public TokenPair generate() {
        byte[] random = new byte[RANDOM_HEX_LENGTH / 2];
        secureRandom.nextBytes(random);
        String plaintext = PREFIX + HexFormat.of().formatHex(random);
        return new TokenPair(plaintext, hash(plaintext));
    }

    @Override
    public String hash(String rawToken) {
        return DocumentHasher.hash(rawToken);
    }
}
