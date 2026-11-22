package com.foxcash.domain.exception;

/**
 * Perfil de operação inexistente ou de outro lojista (Spec 122) — 404
 * {@code profile_not_found} sem revelar existência (padrão IDOR das Specs 24/31).
 */
public class OperationProfileNotFoundException extends RuntimeException {

    public OperationProfileNotFoundException(String message) {
        super(message);
    }
}
