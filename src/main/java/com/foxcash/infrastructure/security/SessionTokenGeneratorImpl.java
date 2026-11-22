package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.SessionTokenGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Implementacao do port {@link SessionTokenGenerator} (Spec 13).
 *
 * <p>Gera token opaco no formato {@code mst_<32 hex>} (SecureRandom) e calcula
 * o hash SHA-256 (hex) — apenas o hash e persistido em
 * {@code master_sessions.token_hash}; o texto claro e devolvido no login.
 */
@Component
public class SessionTokenGeneratorImpl implements SessionTokenGenerator {

    private static final String PREFIX = "mst_";
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
