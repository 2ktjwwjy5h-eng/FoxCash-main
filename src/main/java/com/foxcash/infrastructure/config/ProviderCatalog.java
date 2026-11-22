package com.foxcash.infrastructure.config;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Catálogo de rotas de gateway disponíveis para os perfis de operação do portal
 * (Spec 122): "PADRAO" + os adquirentes **configurados** (Spec 91 —
 * {@code foxcash.psp.provider} + {@code foxcash.psp.routing.providers}).
 *
 * <p>Usado por: {@code GET /api/v1/portal/profile} (availableRoutes de cada
 * perfil) e {@code POST/PATCH /api/v1/portal/profile} (validação da rota — 400
 * {@code unknown_gateway_route} quando fora da lista). Sem adapter novo: o
 * catálogo reflete o que está configurado (hoje pagarme/asaas/mock — Stripe/Global
 * é futuro).</p>
 */
@Component
public class ProviderCatalog {

    private final PspProperties psp;
    private final RoutingProperties routing;

    public ProviderCatalog(PspProperties psp, RoutingProperties routing) {
        this.psp = psp;
        this.routing = routing;
    }

    /**
     * Rotas disponíveis para o perfil: "PADRAO" + adquirentes configurados
     * (dedupe, lowercase, "PADRAO" primeiro).
     */
    public List<String> availableRoutes() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("PADRAO");
        if (psp.getProvider() != null && !psp.getProvider().isBlank()) {
            keys.add(psp.getProvider().toLowerCase());
        }
        for (String k : routing.getProviders().split(",")) {
            if (!k.isBlank()) {
                keys.add(k.trim().toLowerCase());
            }
        }
        return List.copyOf(keys);
    }

    /** A rota está no catálogo? ("PADRAO" conta como rota válida — vira null no perfil.) */
    public boolean contains(String route) {
        if (route == null || route.isBlank()) {
            return true;
        }
        return availableRoutes().contains(route.trim().toLowerCase());
    }
}
