package com.foxcash.domain.exception;

/**
 * Código TOTP/recovery de MFA inválido, expirado, reusado ou senha incorreta na
 * desativação — mapeada para 401 unauthorized (sem oráculo: o mesmo 401 para
 * código errado, master sem MFA ou lockout ativo).
 */
public class InvalidMfaCodeException extends RuntimeException {

    public InvalidMfaCodeException() {
        super("Codigo MFA invalido");
    }
}
