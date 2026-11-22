package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MerchantDashboardUseCase;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ponto de série temporal do portal (Spec 20/21 convenção) — gráfico de linha.
 */
public record PortalTimeSeriesResponse(
        LocalDate label,
        BigDecimal value) {

    public static PortalTimeSeriesResponse from(MerchantDashboardUseCase.TimeSeriesPoint point) {
        return new PortalTimeSeriesResponse(point.label(), point.value().amount());
    }
}
