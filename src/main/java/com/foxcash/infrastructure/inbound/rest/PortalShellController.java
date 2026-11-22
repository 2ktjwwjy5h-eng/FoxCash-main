package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.PortalShellService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: shell do portal (Spec 101) — header global do lojista,
 * role {@code ROLE_MERCHANT_PORTAL} (principal = merchantId). Somente-leitura,
 * aditivo — nenhum contrato existente muda.
 *
 * <p>Fuso de exibição via header {@code X-Timezone-Offset} (minutos; opcional,
 * fallback UTC) — usado SÓ para a saudação/data (negócio sempre em UTC).
 * Escopo do perfil ativo (Specs 124/139) via {@link ProfileScopeResolver}.
 */
@RestController
@RequestMapping("/api/v1/portal/shell")
public class PortalShellController {

    private final PortalShellService shellService;
    private final ProfileScopeResolver scopeResolver;

    public PortalShellController(PortalShellService shellService,
                                 ProfileScopeResolver scopeResolver) {
        this.shellService = shellService;
        this.scopeResolver = scopeResolver;
    }

    /** Agregação única do header (Spec 101 D2.1). */
    @GetMapping
    public PortalShellService.PortalShell shell(
            Authentication authentication,
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Timezone-Offset", required = false) String timezoneOffset) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        UUID profileId = scopeResolver.scopeProfileId(merchantId, httpRequest).orElse(null);
        List<UUID> botIds = scopeResolver.scopeBotIds(merchantId, httpRequest);
        return shellService.shell(merchantId, profileId, botIds, parseOffset(timezoneOffset));
    }

    /** Carrossel de leads (Spec 101 D2.2) — sem o billing. */
    @GetMapping("/recent-leads")
    public List<PortalShellService.ShellLead> recentLeads(
            Authentication authentication,
            HttpServletRequest httpRequest) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        List<UUID> botIds = scopeResolver.scopeBotIds(merchantId, httpRequest);
        return shellService.recentLeads(merchantId, botIds, 8);
    }

    /** Offset em minutos; inválido/fora de faixa → 400 invalid_argument. */
    private static ZoneOffset parseOffset(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            int minutes = Integer.parseInt(raw.trim());
            if (minutes < -720 || minutes > 720) {
                throw new IllegalArgumentException(
                        "X-Timezone-Offset inválido: " + raw + " (esperado minutos entre -720 e 720)");
            }
            return ZoneOffset.ofTotalSeconds(minutes * 60);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "X-Timezone-Offset inválido: " + raw + " (esperado minutos inteiros)");
        }
    }
}
