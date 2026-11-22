package com.foxcash.domain.exception;

/**
 * Escopo de perfil inválido (Spec 139) — 400 {@code invalid_scope}. O escopo é
 * classificação da operação do perfil: apenas GENERAL/BOT_ONLY/GATEWAY_ONLY.
 */
public class InvalidProfileScopeException extends RuntimeException {

    public InvalidProfileScopeException(String message) {
        super(message);
    }
}
