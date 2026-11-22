package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.MerchantSalesUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalSaleDetailResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalSaleResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalSalesMetricsResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Adaptador REST: vendas/operação comercial do lojista no portal (Spec 25),
 * role {@code ROLE_MERCHANT_PORTAL}. Somente-leitura (estorno/reembolso fora
 * de escopo). Parâmetros invalidos → 400 {@code invalid_argument} (mesmo
 * contrato do QueryParamParser).
 */
@RestController
@RequestMapping("/api/v1/portal/sales")
public class PortalSalesController {

    private final MerchantSalesUseCase sales;
    private final ProfileScopeResolver scopeResolver;

    public PortalSalesController(MerchantSalesUseCase sales,
                                 ProfileScopeResolver scopeResolver) {
        this.sales = sales;
        this.scopeResolver = scopeResolver;
    }

    @GetMapping
    public PageResponse<PortalSaleResponse> list(
            Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        UUID profileId = scopeResolver.scopeProfileId(merchantId, httpRequest).orElse(null);
        TransactionStatus statusFilter = QueryParamParser.parseEnum(
                TransactionStatus.class, status, "status");
        Instant fromInstant = QueryParamParser.parseInstant(from, "from");
        Instant toInstant = QueryParamParser.parseInstant(to, "to");
        return PageResponse.from(
                sales.list(merchantId, profileId, statusFilter, fromInstant, toInstant,
                        QueryParamParser.clampPage(page), QueryParamParser.clampSize(size)),
                PortalSaleResponse::from);
    }

    @GetMapping("/metrics")
    public PortalSalesMetricsResponse metrics(
            Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "30") int days) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        UUID profileId = scopeResolver.scopeProfileId(merchantId, httpRequest).orElse(null);
        return PortalSalesMetricsResponse.from(sales.metrics(merchantId, profileId, days));
    }

    @GetMapping("/{transactionId}")
    public PortalSaleDetailResponse detail(Authentication authentication,
                                           jakarta.servlet.http.HttpServletRequest httpRequest,
                                           @PathVariable UUID transactionId) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        UUID profileId = scopeResolver.scopeProfileId(merchantId, httpRequest).orElse(null);
        return PortalSaleDetailResponse.from(sales.detail(merchantId, profileId, transactionId));
    }
}
