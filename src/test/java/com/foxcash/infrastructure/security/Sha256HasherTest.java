package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link Sha256Hasher} (Spec 19): hash SHA-256 (hex) usado para
 * pseudonimizar o buyerId do comprador. Vetores conhecidos NIST FIPS 180-4.
 */
class Sha256HasherTest {

    @Test
    void knownVectorEmptyString() {
        assertThat(Sha256Hasher.hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void knownVectorAbc() {
        assertThat(Sha256Hasher.hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void isDeterministicAndProduces64HexChars() {
        assertThat(Sha256Hasher.hex("buyer-123")).isEqualTo(Sha256Hasher.hex("buyer-123"));
        assertThat(Sha256Hasher.hex("buyer-123")).hasSize(64);
    }

    @Test
    void differsForDifferentInputs() {
        assertThat(Sha256Hasher.hex("a")).isNotEqualTo(Sha256Hasher.hex("b"));
    }
}
