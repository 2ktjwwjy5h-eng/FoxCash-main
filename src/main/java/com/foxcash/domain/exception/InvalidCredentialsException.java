package com.foxcash.domain.exception;

/**
 * Credenciais de master inválidas (usuário inexistente, senha incorreta ou
 * master inativo) — mapeada para 401 unauthorized.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Credenciais invalidas");
    }
}
