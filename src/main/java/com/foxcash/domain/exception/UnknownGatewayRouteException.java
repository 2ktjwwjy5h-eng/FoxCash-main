package com.foxcash.domain.exception;

/**
 * Rota de gateway fora do catálogo configurado (Spec 122) — 400
 * {@code unknown_gateway_route}. O lojista só pode fixar uma rota que existe na
 * lista de adquirentes configurados (PADRAO + provedores).
 */
public class UnknownGatewayRouteException extends RuntimeException {

    public UnknownGatewayRouteException(String message) {
        super(message);
    }
}
