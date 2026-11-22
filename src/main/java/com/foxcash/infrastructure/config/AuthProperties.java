package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao de autenticacao/recuperacao de senha (Spec 40) — {@code foxcash.auth.*}.
 */
@ConfigurationProperties("foxcash.auth")
public class AuthProperties {

    /** Validade do token de recuperacao de senha em segundos (default 30min). */
    private long resetTokenTtlSeconds = 30 * 60;

    /**
     * Devolve o token de recuperacao em claro na resposta do {@code forgot-password}?
     * Dev default {@code true} (testavel sem infra de e-mail); em producao deve ser
     * {@code false} (anti-enumeracao — resposta sempre 204; entrega por canal futuro).
     */
    private boolean exposeResetToken = true;

    /** Falhas seguidas de login do master que disparam o lockout (B-08). */
    private int maxLoginAttempts = 5;

    /** Duracao do lockout do master em minutos (B-08). */
    private long lockoutMinutes = 15;

    public long getResetTokenTtlSeconds() {
        return resetTokenTtlSeconds;
    }

    public void setResetTokenTtlSeconds(long resetTokenTtlSeconds) {
        this.resetTokenTtlSeconds = resetTokenTtlSeconds;
    }

    public boolean isExposeResetToken() {
        return exposeResetToken;
    }

    public void setExposeResetToken(boolean exposeResetToken) {
        this.exposeResetToken = exposeResetToken;
    }

    public int getMaxLoginAttempts() {
        return maxLoginAttempts;
    }

    public void setMaxLoginAttempts(int maxLoginAttempts) {
        this.maxLoginAttempts = maxLoginAttempts;
    }

    public long getLockoutMinutes() {
        return lockoutMinutes;
    }

    public void setLockoutMinutes(long lockoutMinutes) {
        this.lockoutMinutes = lockoutMinutes;
    }
}
