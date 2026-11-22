package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.PortalGatewayService;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayCatalogItemResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayOverviewResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: visão de gateways do lojista no portal (Spec 107), role
 * {@code ROLE_MERCHANT_PORTAL}. Somente-leitura — nunca altera o roteamento
 * (Spec 91 intacta) e nunca expõe segredos dos adquirentes. A ordem/fallback
 * reflete o perfil ativo do header {@code X-Profile-ID} (Spec 139).
 */
@RestController
@RequestMapping("/api/v1/portal/gateways")
public class PortalGatewayController {

    private final PortalGatewayService gatewayService;
    private final ProfileScopeResolver scopeResolver;

    public PortalGatewayController(PortalGatewayService gatewayService,
                                   ProfileScopeResolver scopeResolver) {
        this.gatewayService = gatewayService;
        this.scopeResolver = scopeResolver;
    }

    /** Adquirentes do lojista: ordem de fallback + estado do circuito + extrato. */
    @GetMapping
    public GatewayOverviewResponse overview(Authentication authentication,
                                            HttpServletRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        // Spec 139: perfil ativo (header validado; ausente → rota por risco).
        UUID profileId = scopeResolver.resolveActiveProfileId(merchantId, request).orElse(null);
        return gatewayService.overview(merchantId, profileId);
    }

    /** Catálogo de integrações disponíveis (Pix/Cartão/Cripto — read-only). */
    @GetMapping("/catalog")
    public List<GatewayCatalogItemResponse> catalog() {
        return gatewayService.catalog();
    }
}
