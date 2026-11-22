package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.List;
import java.util.UUID;

/**
 * Perfil de operação na resposta do portal (Spec 122). {@code gatewayRoute} é
 * {@code "PADRAO"} quando nenhuma rota está fixada (null no banco);
 * {@code availableRoutes} = catálogo (PADRAO + adquirentes configurados);
 * {@code bots} = frota do perfil (não-deletados; bot sem perfil → default);
 * {@code scope} (Spec 139) = classificação da operação (GENERAL/BOT_ONLY/GATEWAY_ONLY).
 */
public record ProfileResponse(
        UUID id,
        String name,
        String scope,
        String gatewayRoute,
        List<String> availableRoutes,
        List<ProfileBotResponse> bots) {
}
