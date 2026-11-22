package com.foxcash.domain.service;

import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validação de destino de saque por tipo (Spec 31) — regra pura de domínio.
 *
 * <p>CPF/CNPJ com dígitos verificadores (reuso {@link DocumentValidator},
 * distinguindo pelo comprimento dos dígitos: 11 → CPF, 14 → CNPJ); celular BR
 * no formato {@code +55DDD NNNNNNNNN}; chave aleatória (EVP) como UUID válido;
 * dados bancários com banco/agência/conta obrigatórios (chave Pix opcional).
 * Falha → {@link IllegalArgumentException} (400 {@code invalid_argument}).
 */
public final class WithdrawalDestinationValidator {

    private static final Pattern PHONE = Pattern.compile("^\\+55[1-9]\\d{10,11}$");
    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    private WithdrawalDestinationValidator() {
    }

    public static void validate(WithdrawalDestinationType type, String value,
                                String bankCode, String agency, String account) {
        Objects.requireNonNull(type, "type must not be null");
        switch (type) {
            case PIX_CPF -> requireDocument(value, 11, "CPF");
            case PIX_CNPJ -> requireDocument(value, 14, "CNPJ");
            case PIX_PHONE -> requirePhone(value);
            case PIX_EVP -> requireUuid(value);
            case BANK_ACCOUNT -> {
                requireText(bankCode, "bankCode");
                requireText(agency, "agency");
                requireText(account, "account");
            }
        }
    }

    private static void requireDocument(String value, int length, String name) {
        String digits = NON_DIGITS.matcher(requireText(value, "value")).replaceAll("");
        if (digits.length() != length || !DocumentValidator.isValid(digits)) {
            throw new IllegalArgumentException("Chave Pix " + name + " invalida");
        }
    }

    private static void requirePhone(String value) {
        if (!PHONE.matcher(requireText(value, "value")).matches()) {
            throw new IllegalArgumentException("Celular invalido (esperado +55 DDD numero, ex.: +5511987654321)");
        }
    }

    private static void requireUuid(String value) {
        String raw = requireText(value, "value");
        try {
            // Aceita com ou sem hífens — normaliza para o formato canônico.
            java.util.UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Chave Pix aleatoria (EVP) deve ser um UUID valido");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Campo obrigatorio ausente: " + field);
        }
        return value.trim();
    }
}
