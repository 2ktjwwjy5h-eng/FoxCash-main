package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.infrastructure.security.TurnstileProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Spec 63 (Fase C): entrega a SITE KEY pública do Turnstile (Cloudflare) para a
 * SPA do portal montar o widget antes do login/cadastro.
 *
 * <p>Endpoint público ({@code SecurityConfig} permitAll) — expõe apenas a
 * {@code site-key}, que vai para o browser de qualquer forma; o secret nunca
 * sai do servidor (fica só em env var). Vazio quando o Turnstile não está
 * configurado — a SPA simplesmente não renderiza o widget.
 */
@RestController
public class PortalTurnstileConfigController {

    private final TurnstileProperties properties;

    public PortalTurnstileConfigController(TurnstileProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/v1/portal/turnstile-config")
    public Map<String, String> turnstileConfig() {
        return Map.of("siteKey", properties.getSiteKey());
    }
}
