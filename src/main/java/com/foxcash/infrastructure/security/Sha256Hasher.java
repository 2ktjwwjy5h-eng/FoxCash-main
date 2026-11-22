package com.foxcash.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Hash SHA-256 (hex) generico — usado para pseudonimizar o buyerId do
 * comprador (Spec 19): apenas o hash e persistido, nunca o valor enviado
 * pelo bot (mesmo padrao de {@code document_hash}, {@code api_key_hash} e
 * {@code token_hash}).
 */
public final class Sha256Hasher {

    private Sha256Hasher() {
    }

    public static String hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular hash SHA-256", e);
        }
    }
}
