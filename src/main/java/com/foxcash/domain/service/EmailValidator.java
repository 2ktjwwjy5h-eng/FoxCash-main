package com.foxcash.domain.service;

import java.util.regex.Pattern;

/**
 * Validação simples de e-mail (Spec 39). Regra pura de domínio — sem
 * dependências. Aceita o formato comum {@code local@domínio.tld}; o cadastro
 * exige e-mail válido e o login o usa como identificador (normalizado em
 * minúsculas).
 */
public final class EmailValidator {

    private static final int MAX_LENGTH = 255;

    // Formato pragmático: 1+ chars não-espaço, @, domínio com pelo menos um '.'.
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private EmailValidator() {
    }

    /** @return {@code true} se {@code email} tiver formato válido e tamanho aceitável. */
    public static boolean isValid(String email) {
        return email != null
                && email.length() <= MAX_LENGTH
                && EMAIL.matcher(email.trim()).matches();
    }
}
