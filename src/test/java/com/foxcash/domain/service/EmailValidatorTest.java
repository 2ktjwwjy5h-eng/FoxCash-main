package com.foxcash.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do {@link EmailValidator} (Spec 39): formato simples
 * {@code local@domínio.tld} — o cadastro exige e-mail válido e o login o usa
 * como identificador (normalizado em minúsculas).
 */
class EmailValidatorTest {

    @Test
    void acceptsValidEmails() {
        assertTrue(EmailValidator.isValid("loja@example.com"));
        assertTrue(EmailValidator.isValid("loja.a@example.com.br"));
        assertTrue(EmailValidator.isValid("user+tag@example.co"));
        assertTrue(EmailValidator.isValid("nome.sobrenome@sub.dominio.com"));
        assertTrue(EmailValidator.isValid("a@b.co"));
    }

    @Test
    void rejectsInvalidEmails() {
        assertFalse(EmailValidator.isValid(null));
        assertFalse(EmailValidator.isValid(""));
        assertFalse(EmailValidator.isValid("   "));
        assertFalse(EmailValidator.isValid("email-invalido"));
        assertFalse(EmailValidator.isValid("sem-arroba.com"));
        assertFalse(EmailValidator.isValid("com espaco@example.com"));
        assertFalse(EmailValidator.isValid("@sem-local.com"));
        assertFalse(EmailValidator.isValid("local@sem-dominio"));
        assertFalse(EmailValidator.isValid("local@dominio"));
        assertFalse(EmailValidator.isValid("a".repeat(256) + "@example.com")); // > 255
    }

    @Test
    void acceptsExactlyMaxLengthEmail() {
        // Mutation (ConditionalsBoundary <= <-> <): e-mail de 255 chars é aceito.
        assertTrue(EmailValidator.isValid("a".repeat(249) + "@x.com")); // 255 exatos
    }
}
