package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config do device fingerprint do portal (Spec 141) — {@code foxcash.fingerprint.*}.
 *
 * <p>Sinal de risco PASSIVO: o frontend envia um hash SHA-256 (64 hex) dos
 * sinais do navegador no cadastro/login; o backend grava e associa ao lojista,
 * mas NUNCA bloqueia. O operador é avisado via webhook
 * ({@code FINGERPRINT_DEVICE_SHARED}, padrão Spec 64/HONEYPOT) quando o mesmo
 * dispositivo atinge {@code alert-threshold} contas distintas — com throttle
 * por hash (anti-spam). Kill-switch por env {@code FOXCASH_FINGERPRINT_ENABLED}.
 */
@ConfigurationProperties("foxcash.fingerprint")
public class FingerprintProperties {

    /** Grava o fingerprint recebido no cadastro/login. {@code false} = ignora. */
    private boolean enabled = true;

    /**
     * Lojistas distintos com o MESMO hash que disparam o alerta
     * {@code FINGERPRINT_DEVICE_SHARED} (default 2 = dispositivo visto em 2+
     * contas). 0/negativo = nunca alerta.
     */
    private int alertThreshold = 2;

    /** Throttle por hash: 1 alerta a cada intervalo (default 300s = 5min). */
    private long alertThrottleSeconds = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(int alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    public long getAlertThrottleSeconds() {
        return alertThrottleSeconds;
    }

    public void setAlertThrottleSeconds(long alertThrottleSeconds) {
        this.alertThrottleSeconds = alertThrottleSeconds;
    }
}
