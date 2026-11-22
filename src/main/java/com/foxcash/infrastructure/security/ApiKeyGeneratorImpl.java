package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.ApiKeyGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Implementacao do port {@link ApiKeyGenerator} (Spec 12).
 *
 * <p>Gera a chave de API do lojista no formato {@code mch_<32 hex>} (SecureRandom)
 * e calcula o hash SHA-256 (hex) — apenas o hash e persistido
 * ({@code merchants.api_key_hash}), o texto claro e devolvido uma unica vez no
 * cadastro.
 */
@Component
public class ApiKeyGeneratorImpl implements ApiKeyGenerator {

    private static final String PREFIX = "mch_";
    private static final int RANDOM_HEX_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public ApiKeyPair generate() {
        byte[] random = new byte[RANDOM_HEX_LENGTH / 2];
        secureRandom.nextBytes(random);
        String plaintext = PREFIX + HexFormat.of().formatHex(random);
        return new ApiKeyPair(plaintext, DocumentHasher.hash(plaintext));
    }
}
