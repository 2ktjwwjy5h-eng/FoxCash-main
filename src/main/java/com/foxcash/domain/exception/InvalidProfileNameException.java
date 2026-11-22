package com.foxcash.domain.exception;

/**
 * Nome inválido de perfil de operação (Spec 122) — 400 {@code invalid_name}
 * (ausente/vazio ou acima de 64 caracteres).
 */
public class InvalidProfileNameException extends RuntimeException {

    public InvalidProfileNameException(String message) {
        super(message);
    }
}
