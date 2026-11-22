package com.foxcash.domain.service;

/**
 * Politica de senha da plataforma (auditoria de homologacao 2026-08-05/B-07).
 *
 * <p>Antes havia apenas "8 a 72 caracteres". A politica atual adiciona
 * complexidade minima: pelo menos uma letra minuscula, uma maiuscula e um
 * digito (tres classes). Aplicada a TODOS os pontos onde o usuario escolhe a
 * senha: cadastro do lojista (Spec 39) e redefinicao por token (Spec 40).
 * Senhas geradas pelo sistema (perfil master, Spec 41) nao passam por aqui —
 * sao aleatorias (SecureRandom) e satisfazem a politica por construcao.
 *
 * <p>Teto de 72 caracteres = limite do BCrypt (bytes da senha).
 */
public final class PasswordPolicy {

    /** Tamanho minimo da senha. */
    public static final int MIN_LENGTH = 8;

    /** Tamanho maximo da senha (limite de bytes do BCrypt). */
    public static final int MAX_LENGTH = 72;

    private PasswordPolicy() {
    }

    /**
     * Valida a senha escolhida pelo usuario.
     *
     * @throws IllegalArgumentException quando a senha viola a politica
     *                                  (400 {@code invalid_argument} no REST)
     */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("senha deve ter entre 8 e 72 caracteres");
        }
        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLower || !hasUpper || !hasDigit) {
            throw new IllegalArgumentException(
                    "senha deve conter letras minusculas, maiusculas e numeros");
        }
    }
}
