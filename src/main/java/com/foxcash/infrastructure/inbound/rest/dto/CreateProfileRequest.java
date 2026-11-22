package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Criação de perfil de operação (Spec 122): {@code name} obrigatório (1..64);
 * {@code gatewayRoute} opcional (PADRAO/null → roteamento por risco; validado no
 * catálogo → 400 {@code unknown_gateway_route}); {@code scope} opcional (Spec
 * 139 — GENERAL default; BOT_ONLY/GATEWAY_ONLY; inválido → 400
 * {@code invalid_scope}; BOT_ONLY + rota → 400 {@code route_not_allowed_for_scope}).
 */
public record CreateProfileRequest(
        String name,
        String gatewayRoute,
        String scope) {
}
