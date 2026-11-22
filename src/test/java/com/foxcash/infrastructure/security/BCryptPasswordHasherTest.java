package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link BCryptPasswordHasher} (Spec 13): hash lento e salteado para
 * senhas de operadores humanos — nunca armazena o valor em claro.
 */
class BCryptPasswordHasherTest {

    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher();

    @Test
    void hashProducesBcryptHashDifferentFromPlaintext() {
        String hash = hasher.hash("senha-forte");

        assertThat(hash).startsWith("$2");
        assertThat(hash).isNotEqualTo("senha-forte");
    }

    @Test
    void matchesVerifiesCorrectPassword() {
        String hash = hasher.hash("senha-forte");

        assertThat(hasher.matches("senha-forte", hash)).isTrue();
        assertThat(hasher.matches("senha-errada", hash)).isFalse();
    }

    @Test
    void samePasswordProducesDifferentSaltsButBothMatch() {
        String first = hasher.hash("senha-forte");
        String second = hasher.hash("senha-forte");

        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches("senha-forte", first)).isTrue();
        assertThat(hasher.matches("senha-forte", second)).isTrue();
    }
}
