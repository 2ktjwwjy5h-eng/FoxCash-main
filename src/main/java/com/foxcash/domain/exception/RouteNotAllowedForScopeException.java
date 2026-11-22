package com.foxcash.domain.exception;

/**
 * Perfil {@code BOT_ONLY} com rota de gateway (Spec 139) — 400
 * {@code route_not_allowed_for_scope}. Perfil de bot nunca fixa rota: o
 * roteamento é sempre por risco (Spec 91).
 */
public class RouteNotAllowedForScopeException extends RuntimeException {

    public RouteNotAllowedForScopeException(String message) {
        super(message);
    }
}
