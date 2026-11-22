package com.foxcash.infrastructure.inbound.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Spec 46 — fallback da SPA do portal (React/Vite): com
 * {@code foxcash.portal.serve-frontend=true}, as rotas client-side do
 * react-router (/login, /dashboard, ...) são encaminhadas para o
 * {@code index.html} servido de {@code src/main/resources/static} — o
 * roteamento acontece no browser. Sem o flag, o bean não existe e nenhuma
 * rota extra é exposta (a API pura permanece sem HTML).
 *
 * <p>Nota: os paths são um literal de array na anotação — campos
 * {@code String[]} (mesmo {@code static final}) não são constantes de
 * compilação (JLS 4.12.4) e anotações exigem constantes.
 */
@Controller
@ConditionalOnProperty(name = "foxcash.portal.serve-frontend", havingValue = "true")
public class PortalSpaController {

    @RequestMapping(
            path = {"/", "/login", "/register", "/forgot-password", "/reset-password", "/dashboard",
                    "/bots", "/bots/creator/telegram", "/bots/creator/discord", "/wallet", "/charges", "/sales",
                    "/destinations", "/withdraw", "/webhooks", "/credentials", "/security",
                    // Spec 80: editor/lista de broadcast (rotas client-side do react-router).
                    "/broadcasts", "/broadcasts/*",
                    // Spec 82: painel de tracking (rota client-side do react-router).
                    "/tracking", "/tracking/*",
                    // Spec 84: painel de clientes/CRM (rota client-side do react-router).
                    "/crm", "/crm/*",
                    // Spec 108: visão de gateways do lojista (rota client-side do react-router).
                    "/gateways",
                    // Spec 119: Comunidade (rota client-side do react-router).
                    "/community"},
            method = RequestMethod.GET)
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
