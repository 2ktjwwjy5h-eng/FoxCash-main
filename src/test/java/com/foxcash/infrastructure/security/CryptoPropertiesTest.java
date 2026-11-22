package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link CryptoProperties} — auditoria 2026-08-05 (B-A2): fail-fast
 * no boot para segredo ausente, placeholder não-resolvido ou curto demais
 * (entropia insuficiente para derivar a chave AES-256).
 */
class CryptoPropertiesTest {

    private final CryptoProperties properties = new CryptoProperties();

    @Test
    void rejectsBlankSecret() {
        properties.setSecret("   ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("obrigatorio");
    }

    @Test
    void rejectsUnresolvedPlaceholder() {
        properties.setSecret("${FOXCASH_CRYPTO_SECRET}");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nao foi resolvido");
    }

    @Test
    void rejectsShortSecret() {
        properties.setSecret("short-secret");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("muito curto");
    }

    @Test
    void acceptsLongSecret() {
        properties.setSecret("0123456789abcdef0123456789abcdef"); // 32 chars
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
