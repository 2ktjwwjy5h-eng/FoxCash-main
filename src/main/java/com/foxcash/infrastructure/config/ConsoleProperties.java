package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do Master Console (Spec 71, fatia 1) — {@code foxcash.console.*}.
 * Espelho minimo do {@link PortalProperties}: o console e servido pelo proprio
 * Spring Boot no MESMO origin (sem CORS); o bloco CORS existente do portal
 * permanece o unico gate para consumo externo.
 */
@ConfigurationProperties("foxcash.console")
public class ConsoleProperties {

    /**
     * Spec 71: serva a SPA do console (build estatico em
     * {@code src/main/resources/static/console}) — {@code foxcash.console.serve-frontend}.
     * Default {@code false}: mantem a CSP restrita da API pura e nenhuma rota
     * extra exposta. Com {@code true}, ativa a CSP da SPA, as rotas publicas
     * (/console, /console/**) e o fallback para o {@code console/index.html}.
     */
    private boolean serveFrontend = false;

    public boolean isServeFrontend() {
        return serveFrontend;
    }

    public void setServeFrontend(boolean serveFrontend) {
        this.serveFrontend = serveFrontend;
    }
}
