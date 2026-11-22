package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantDashboardUseCase;

/**
 * Ponto de atenção do dashboard do lojista (Spec 21).
 */
public record PortalAttentionPointResponse(
        String type,
        String severity,
        String message) {

    public static PortalAttentionPointResponse from(MerchantDashboardUseCase.AttentionPoint point) {
        return new PortalAttentionPointResponse(point.type(), point.severity(), point.message());
    }
}
