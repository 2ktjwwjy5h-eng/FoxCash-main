package com.foxcash.domain.exception;

/**
 * Perfil {@code GATEWAY_ONLY} com frota de bots (Spec 139) — 400
 * {@code bots_not_allowed_for_scope}. Perfil de gateway não carrega bots: a
 * frota é desanexada ao mudar o escopo para GATEWAY_ONLY.
 */
public class BotsNotAllowedForScopeException extends RuntimeException {

    public BotsNotAllowedForScopeException(String message) {
        super(message);
    }
}
