package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.ResetTokenGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link ResetTokenGeneratorImpl} (Spec 40): formato {@code rst_<32 hex>}
 * e hash SHA-256 — apenas o hash é persistido.
 */
class ResetTokenGeneratorImplTest {

    private final ResetTokenGeneratorImpl generator = new ResetTokenGeneratorImpl();

    @Test
    void generatesTokenWithResetPrefixAnd32HexChars() {
        ResetTokenGenerator.TokenPair pair = generator.generate();

        assertThat(pair.plaintext()).matches("^rst_[0-9a-f]{32}$");
    }

    @Test
    void generatesUniqueTokens() {
        assertThat(generator.generate().plaintext()).isNotEqualTo(generator.generate().plaintext());
    }

    @Test
    void hashIsSha256HexOfPlaintext() {
        ResetTokenGenerator.TokenPair pair = generator.generate();

        assertThat(pair.hash()).matches("^[0-9a-f]{64}$");
        assertThat(pair.hash()).isEqualTo(DocumentHasher.hash(pair.plaintext()));
    }

    @Test
    void hashMethodDelegatesToDocumentHasher() {
        assertThat(generator.hash("abc")).isEqualTo(DocumentHasher.hash("abc"));
    }
}
