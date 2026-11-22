package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.MerchantDashboardUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PortalDashboardSummaryResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalTimeSeriesResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalRecentSaleResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalAttentionPointResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: dashboard do lojista no portal (Spec 21), role
 * {@code ROLE_MERCHANT_PORTAL}. Somente-leitura. Parâmetros invalidos → 400
 * {@code invalid_argument} (mesmo contrato do QueryParamParser).
 */
@RestController
@RequestMapping("/api/v1/portal/dashboard")
public class PortalDashboardController {

    private final MerchantDashboardUseCase dashboard;
    private final ProfileScopeResolver scopeResolver;

    public PortalDashboardController(MerchantDashboardUseCase dashboard,
                                     ProfileScopeResolver scopeResolver) {
        this.dashboard = dashboard;
        this.scopeResolver = scopeResolver;
    }

    @GetMapping("/summary")
    public PortalDashboardSummaryResponse summary(
            Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // Spec 124: escopo do header (ausente → lojista inteiro; inválido → vazio).
        UUID profileId = scopeResolver.scopeProfileId(merchantId, httpRequest).orElse(null);
        Instant fromInstant = QueryParamParser.parseInstant(from, "from");
        Instant toInstant = QueryParamParser.parseInstant(to, "to");
        Instant[] window = monthWindow(fromInstant, toInstant);
        return PortalDashboardSummaryResponse.from(
                dashboard.summary(merchantId, profileId, window[0], window[1]));
    }

    @GetMapping("/revenue-series")
    public List<PortalTimeSeriesResponse> revenueSeries(
            Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        UUID profileId = scopeResolver.scopeProfileId(merchantId, httpRequest).orElse(null);
        Instant fromInstant = QueryParamParser.parseInstant(from, "from");
        Instant toInstant = QueryParamParser.parseInstant(to, "to");
        Instant[] window = monthWindow(fromInstant, toInstant);
        return dashboard.revenueSeries(merchantId, profileId, window[0], window[1]).stream()
                .map(PortalTimeSeriesResponse::from)
                .toList();
    }

    @GetMapping("/recent-sales")
    public List<PortalRecentSaleResponse> recentSales(
            Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "5") int limit) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        UUID profileId = scopeResolver.scopeProfileId(merchantId, httpRequest).orElse(null);
        int clamped = Math.min(Math.max(limit, 1), 50);
        return dashboard.recentSales(merchantId, profileId, clamped).stream()
                .map(PortalRecentSaleResponse::from)
                .toList();
    }

    @GetMapping("/attention-points")
    public List<PortalAttentionPointResponse> attentionPoints(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        return dashboard.attentionPoints(merchantId).stream()
                .map(PortalAttentionPointResponse::from)
                .toList();
    }

    /** Janela padrão: mês corrente (UTC) quando from/to ausentes. */
    private Instant[] monthWindow(Instant from, Instant to) {
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant start = from != null ? from : today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to != null ? to : now;
        return new Instant[]{start, end};
    }
}
