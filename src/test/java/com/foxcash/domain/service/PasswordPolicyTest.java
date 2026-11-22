package com.foxcash.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes da {@link PasswordPolicy} — auditoria 2026-08-05 (B-07): além de
 * 8–72 caracteres, a senha exige letras minúsculas, maiúsculas e números.
 */
class PasswordPolicyTest {

    @Test
    void acceptsPasswordWithLengthAndThreeClasses() {
        PasswordPolicy.validate("Senha-segura-123");
    }

    @Test
    void rejectsShortPassword() {
        assertThatThrownBy(() -> PasswordPolicy.validate("Ab1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 e 72");
    }

    @Test
    void rejectsOversizedPassword() {
        assertThatThrownBy(() -> PasswordPolicy.validate("A1".repeat(40))) // 80 chars
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 e 72");
    }

    @Test
    void rejectsPasswordWithoutUppercase() {
        assertThatThrownBy(() -> PasswordPolicy.validate("tudo-minusculo-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maiusculas");
    }

    @Test
    void rejectsPasswordWithoutLowercase() {
        assertThatThrownBy(() -> PasswordPolicy.validate("TUDO-MAIUSCULO-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minusculas");
    }

    @Test
    void rejectsPasswordWithoutDigit() {
        assertThatThrownBy(() -> PasswordPolicy.validate("SoLetrasCompleta"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeros");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactlyMinLength() {
        // Mutation (ConditionalsBoundary < <-> <=): senha de 8 chars é aceita.
        PasswordPolicy.validate("Aa1aaaaa"); // 8 chars, com as 3 classes
    }

    @Test
    void acceptsExactlyMaxLength() {
        // Mutation (ConditionalsBoundary > <-> >=): senha de 72 chars é aceita.
        PasswordPolicy.validate("Aa1" + "a".repeat(69)); // 72 chars
    }
}
