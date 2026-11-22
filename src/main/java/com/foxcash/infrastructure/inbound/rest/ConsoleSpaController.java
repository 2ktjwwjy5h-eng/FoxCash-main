package com.foxcash.infrastructure.inbound.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Spec 71 — fallback da SPA do Master Console (React/Vite): com
 * {@code foxcash.console.serve-frontend=true}, as rotas client-side do
 * react-router (/console/dashboard, /console/merchants, ...) são encaminhadas
 * para o {@code console/index.html} servido de
 * {@code src/main/resources/static/console} — o roteamento acontece no browser.
 * Sem o flag, o bean não existe → nenhuma rota extra é exposta (a API pura
 * permanece sem HTML; qualquer rota fora da API cai em
 * {@code anyRequest().authenticated()} → 401).
 *
 * <p><b>Nota (desvio deliberado do D4.1 literal):</b> os paths são EXPLÍCITOS
 * (padrão exato do {@link PortalSpaController}) e não {@code /console/**} — um
 * mapping amplo sombrearia os assets do build ({@code /console/assets/**},
 * {@code base: '/console/'} no Vite) que precisam ser servidos pelo handler de
 * recursos estáticos (a ordem do Spring MVC coloca os controllers à frente dos
 * resources — assets virados em {@code index.html} quebrariam a SPA). Os paths
 * são literais na anotação: campos {@code String[]} (mesmo {@code static final})
 * não são constantes de compilação (JLS 4.12.4).
 */
@Controller
@ConditionalOnProperty(name = "foxcash.console.serve-frontend", havingValue = "true")
public class ConsoleSpaController {

    @RequestMapping(
            path = {"/console", "/console/", "/console/login", "/console/dashboard", "/console/transactions",
                    "/console/merchants", "/console/merchants/*",
                    // Spec 100: config fina por lojista (rota client-side do react-router).
                    "/console/merchants/*/config",
                    "/console/withdrawals",
                    "/console/financial", "/console/receivables", "/console/audit",
                    "/console/activity", "/console/reconciliation", "/console/lookup",
                    "/console/bots",
                    // Spec 89: auditoria de broadcasts (rota client-side do react-router).
                    "/console/broadcasts",
                    // Spec 127: trace de transação (rota client-side do react-router).
                    "/console/trace",
                    "/console/security", "/console/profiles"},
            method = RequestMethod.GET)
    public String forwardToIndex() {
        return "forward:/console/index.html";
    }
}
