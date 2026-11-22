package com.foxcash.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do rate limiting (Spec 18, ADR-009; Spec 43 — M-05).
 *
 * <p>Janela fixa por IP + rota, aplicada às rotas públicas de abuso (cadastro
 * de lojista, logins, forgot/reset). O store é selecionável: {@code memory}
 * (default — single-instance, sem dependência externa) ou {@code redis}
 * (multi-instância — store distribuído, ADR-010).
 */
@ConfigurationProperties("foxcash.security.rate-limit")
public class RateLimitProperties {

    /** Liga/desliga o filtro (dev/tests podem desligar). */
    private boolean enabled = true;

    /** Máximo de requisições permitidas por IP+rota na janela. */
    private int maxRequests = 20;

    /** Tamanho da janela em segundos. */
    private int windowSeconds = 60;

    /**
     * Store do contador: {@code memory} (default — janela em ConcurrentHashMap,
     * topologia single-instance) ou {@code redis} (multi-instância — script Lua
     * atômico, fail-open se indisponível; ADR-010). Env: {@code FOXCASH_RATE_LIMIT_STORE}.
     */
    private String store = "memory";

    /** Rate limit por PRINCIPAL autenticado (Spec 58 — chave API/token, por lojista). */
    private final Authenticated authenticated = new Authenticated();

    /** Rate limit por IP em rotas transacionais (Spec 58 — rede de segurança). */
    private final Transactional transactional = new Transactional();

    /** Rate limit por IP nos webhooks públicos (Spec 59 — flood de /webhooks/pix e /webhooks/payouts). */
    private final Webhook webhook = new Webhook();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public Authenticated getAuthenticated() {
        return authenticated;
    }

    public Transactional getTransactional() {
        return transactional;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    /** Rate limit por principal autenticado (Spec 58) — baseline de calibração. */
    public static class Authenticated {
        private boolean enabled = true;
        private int maxRequests = 120;
        private int windowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Rate limit por IP em rotas transacionais (Spec 58) — baseline de calibração. */
    public static class Transactional {
        private boolean enabled = true;
        private int maxRequests = 300;
        private int windowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Rate limit por IP nos webhooks (Spec 59) — baseline de calibração. */
    public static class Webhook {
        private boolean enabled = true;
        private int maxRequests = 120;
        private int windowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
