package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao dos alertas de seguranca (Spec 64) — {@code foxcash.security.alert.*}.
 *
 * <p>Limiares e janelas por tipo de alerta + janela de throttle (anti-spam:
 * no maximo 1 evento por tipo por janela, mesmo padrao do
 * {@code WebhookSignatureFilter} Spec 59). Os alertas sao indicadores — a
 * decisao/ação continua sendo do operador (filosofia Spec 16/64).
 */
@ConfigurationProperties("foxcash.security.alert")
public class SecurityAlertProperties {

    /** Liga/desliga os alertas de seguranca (dev/tests podem desligar). */
    private boolean enabled = true;

    /** Store dos contadores de rajada (Spec 66 — Fase B): `memory` (default,
     * por-instância) | `redis` (agregado entre réplicas, fail-open). */
    private String store = "memory";

    /** Janela de throttle global (1 evento por tipo neste intervalo). */
    private long throttleSeconds = 300;

    /** Rajada de falhas de login (default 10 em 60s). */
    private LoginFailure loginFailure = new LoginFailure();

    /** Rajada de 429 num grupo de rate limit (default 20 em 60s). */
    private RateLimit rateLimit = new RateLimit();

    /** Rajada de webhooks com assinatura invalida (default 20 em 60s). */
    private SignatureFailure signatureFailure = new SignatureFailure();

    /** Rajada de bloqueios por inspecao de corpo (default 5 em 60s — padroes de
     * alta confianca; 5 payloads de ataque em 1 min ja e ataque automatizado). */
    private BodyInspection bodyInspection = new BodyInspection();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public long getThrottleSeconds() {
        return throttleSeconds;
    }

    public void setThrottleSeconds(long throttleSeconds) {
        this.throttleSeconds = throttleSeconds;
    }

    public LoginFailure getLoginFailure() {
        return loginFailure;
    }

    public void setLoginFailure(LoginFailure loginFailure) {
        this.loginFailure = loginFailure;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public SignatureFailure getSignatureFailure() {
        return signatureFailure;
    }

    public void setSignatureFailure(SignatureFailure signatureFailure) {
        this.signatureFailure = signatureFailure;
    }

    public BodyInspection getBodyInspection() {
        return bodyInspection;
    }

    public void setBodyInspection(BodyInspection bodyInspection) {
        this.bodyInspection = bodyInspection;
    }

    /** Alerta de falhas de login (LOGIN_FAILURE_BURST). */
    public static class LoginFailure {
        private int threshold = 10;
        private long windowSeconds = 60;

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Alerta de rajada de 429 (RATE_LIMIT_BURST). */
    public static class RateLimit {
        private int threshold = 20;
        private long windowSeconds = 60;

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Alerta de rajada de assinatura invalida (SIGNATURE_FAILURE_BURST). */
    public static class SignatureFailure {
        private int threshold = 20;
        private long windowSeconds = 60;

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    /** Alerta de rajada de bloqueios por inspecao de corpo (BODY_INSPECTION_BLOCKED). */
    public static class BodyInspection {
        private int threshold = 5;
        private long windowSeconds = 60;

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }
}
