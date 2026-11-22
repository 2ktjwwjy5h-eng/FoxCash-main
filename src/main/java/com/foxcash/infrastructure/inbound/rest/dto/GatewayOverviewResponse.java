package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.List;

/**
 * Resposta da visão de gateways do portal (Spec 107): adquirentes do lojista
 * na ordem de fallback da policy (Spec 91). Lista vazia quando não há
 * providers configurados (sem erro).
 */
public record GatewayOverviewResponse(List<GatewayProviderResponse> providers) {
}
