package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do CryptoConverter (AES-256/GCM): roundtrip, nao-determinismo,
 * prefixo de versao e deteccao de adulteracao (tag GCM).
 */
class CryptoConverterTest {

    private final CryptoConverter converter =
            new CryptoConverter(properties("chave-de-teste-segura-32bytes!"));

    private static CryptoProperties properties(String secret) {
        CryptoProperties props = new CryptoProperties();
        props.setSecret(secret);
        return props;
    }

    @Test
    void roundtrip_encryptThenDecryptReturnsPlaintext() {
        String plain = "12345678000199";
        String encrypted = converter.convertToDatabaseColumn(plain);
        assertEquals(plain, converter.convertToEntityAttribute(encrypted));
    }

    @Test
    void ciphertextDiffersFromPlaintextAndIsVersioned() {
        String encrypted = converter.convertToDatabaseColumn("12345678000199");
        assertNotEquals("12345678000199", encrypted);
        assertTrue(encrypted.startsWith(CryptoConverter.VERSION));
    }

    @Test
    void ciphertextIsNonDeterministic() {
        String first = converter.convertToDatabaseColumn("12345678000199");
        String second = converter.convertToDatabaseColumn("12345678000199");
        assertNotEquals(first, second);
    }

    @Test
    void tamperedCiphertextFails() {
        String encrypted = converter.convertToDatabaseColumn("12345678000199");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";
        assertThrows(IllegalStateException.class, () -> converter.convertToEntityAttribute(tampered));
    }

    @Test
    void nullPassthrough() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void blankSecretIsRejected() {
        assertThrows(IllegalStateException.class, () -> new CryptoConverter(properties(" ")));
    }

    @Test
    void nullSecretIsRejected() {
        assertThrows(IllegalStateException.class, () -> new CryptoConverter(properties(null)));
    }

    @Test
    void unresolvedPlaceholderSecretIsRejected() {
        // Simula o comportamento observado do Binder: placeholder nao-resolvido
        // vira string literal (ex.: "${FOXCASH_CRYPTO_SECRET}") — chave publica.
        assertThrows(IllegalStateException.class,
                () -> new CryptoConverter(properties("${FOXCASH_CRYPTO_SECRET}")));
    }
}
