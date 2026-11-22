package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.SessionTokenGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link SessionTokenGeneratorImpl} (Spec 13): formato {@code mst_<32 hex>}
 * e hash SHA-256 — apenas o hash é persistido.
 */
class SessionTokenGeneratorImplTest {

    private final SessionTokenGeneratorImpl generator = new SessionTokenGeneratorImpl();

    @Test
    void generatesTokenWithMasterSessionPrefixAnd32HexChars() {
        SessionTokenGenerator.TokenPair pair = generator.generate();

        assertThat(pair.plaintext()).matches("^mst_[0-9a-f]{32}$");
    }

    @Test
    void generatesUniqueTokens() {
        assertThat(generator.generate().plaintext()).isNotEqualTo(generator.generate().plaintext());
    }

    @Test
    void hashIsSha256HexOfPlaintext() {
        SessionTokenGenerator.TokenPair pair = generator.generate();

        assertThat(pair.hash()).matches("^[0-9a-f]{64}$");
        assertThat(pair.hash()).isEqualTo(DocumentHasher.hash(pair.plaintext()));
    }

    @Test
    void hashMethodDelegatesToDocumentHasher() {
        assertThat(generator.hash("abc")).isEqualTo(DocumentHasher.hash("abc"));
    }
}
