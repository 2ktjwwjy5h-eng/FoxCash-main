package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.ApiKeyGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link ApiKeyGeneratorImpl} (Spec 12): formato da chave e hash SHA-256.
 */
class ApiKeyGeneratorTest {

    private final ApiKeyGeneratorImpl generator = new ApiKeyGeneratorImpl();

    @Test
    void generatesKeyWithMerchantPrefixAnd32HexChars() {
        ApiKeyGenerator.ApiKeyPair pair = generator.generate();

        assertThat(pair.plaintext()).matches("^mch_[0-9a-f]{32}$");
    }

    @Test
    void generatesUniqueKeys() {
        String key1 = generator.generate().plaintext();
        String key2 = generator.generate().plaintext();

        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void hashIsSha256HexOfPlaintext() {
        ApiKeyGenerator.ApiKeyPair pair = generator.generate();

        assertThat(pair.hash()).matches("^[0-9a-f]{64}$");
        assertThat(pair.hash()).isEqualTo(DocumentHasher.hash(pair.plaintext()));
        assertThat(pair.hash()).isNotEqualTo(pair.plaintext());
    }
}
