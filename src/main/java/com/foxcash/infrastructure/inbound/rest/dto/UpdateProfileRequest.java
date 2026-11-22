package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Atualização parcial de perfil de operação (Spec 122): campos presentes são
 * aplicados (padrão PUT parcial/Spec 48). Rota validada no catálogo → 400
 * {@code unknown_gateway_route}; nome inválido → 400 {@code invalid_name}.
 * {@code scope} (Spec 139): inválido → 400 {@code invalid_scope}; BOT_ONLY +
 * rota → 400 {@code route_not_allowed_for_scope} (rota forçada a null);
 * GATEWAY_ONLY → frota desanexada na mesma transação.
 */
public record UpdateProfileRequest(
        String name,
        String gatewayRoute,
        String scope) {
}
