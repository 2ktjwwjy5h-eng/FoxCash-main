package com.foxcash.domain.service;

import java.util.regex.Pattern;

/**
 * Validação de documento brasileiro (Spec 18) — base do KYC (políticas §3.1).
 *
 * <p>Aceita CPF (11 dígitos) e CNPJ (14 dígitos), com ou sem máscara
 * (pontos/traço/barra são removidos antes da checagem). Valida tamanho e
 * dígitos verificadores; rejeita sequências de dígitos repetidos (ex.: 111…).
 * Regra pura de domínio — sem dependências.
 */
public final class DocumentValidator {

    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    private DocumentValidator() {
    }

    /**
     * @return {@code true} se {@code document} for um CPF ou CNPJ válido.
     */
    public static boolean isValid(String document) {
        if (document == null) {
            return false;
        }
        String digits = NON_DIGITS.matcher(document).replaceAll("");
        if (digits.length() == 11) {
            return isValidCpf(digits);
        }
        return digits.length() == 14 && isValidCnpj(digits);
    }

    private static boolean isValidCpf(String cpf) {
        if (cpf.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigits(cpf, 9, new int[]{10, 9, 8, 7, 6, 5, 4, 3, 2})
                && checkDigits(cpf, 10, new int[]{11, 10, 9, 8, 7, 6, 5, 4, 3, 2});
    }

    private static boolean isValidCnpj(String cnpj) {
        if (cnpj.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigits(cnpj, 12, new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2})
                && checkDigits(cnpj, 13, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
    }

    /**
     * Confere o dígito verificador na posição {@code position} (0-based):
     * soma {@code digit * weight} dos dígitos anteriores, resto 11 → o dígito
     * verificador é {@code resto < 2 ? 0 : 11 - resto}.
     */
    private static boolean checkDigits(String number, int position, int[] weights) {
        int sum = 0;
        for (int i = 0; i < position; i++) {
            sum += (number.charAt(i) - '0') * weights[i];
        }
        int remainder = sum % 11;
        int expected = remainder < 2 ? 0 : 11 - remainder;
        return expected == number.charAt(position) - '0';
    }
}
