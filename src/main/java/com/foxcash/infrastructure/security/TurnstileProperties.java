package com.foxcash.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuracao do Turnstile (Cloudflare) — bot mitigation no login/cadastro
 * (Spec 63, Fase C; ADR-014). {@code foxcash.security.turnstile.*}.
 *
 * <p><b>Default OFF</b> (dev/CI/E2E sem chaves): a validacao server-side so
 * passa a exigir token com {@code enabled=true} E {@code secret-key} presente.
 * A {@code site-key} e publica (frontend/portal — vai para o browser); a
 * {@code secret-key} so existe em env var (nunca no yml de prod).
 *
 * <p>O {@code timeout} (default 5s) cobre connect+read do siteverify; acima
 * disso a verificacao faz <b>fail-open</b> com WARN throttled (disponibilidade
 * primeiro — uma queda do Turnstile nunca derruba o login, mesmo padrao ADR-010).
 */
@ConfigurationProperties("foxcash.security.turnstile")
public class TurnstileProperties {

    /** Liga a validacao server-side (default false). Env: {@code FOXCASH_TURNSTILE_ENABLED}. */
    private boolean enabled = false;

    /** Site key publica (frontend). Env: {@code FOXCASH_TURNSTILE_SITE_KEY}. */
    private String siteKey = "";

    /** Secret key (server-side siteverify). Env: {@code FOXCASH_TURNSTILE_SECRET_KEY}. */
    private String secretKey = "";

    /** Timeout do siteverify (connect + read). Env: {@code FOXCASH_TURNSTILE_TIMEOUT}. */
    private Duration timeout = Duration.ofSeconds(5);

    /** Endpoint siteverify do Cloudflare (override apenas em testes). */
    private String verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public void setSiteKey(String siteKey) {
        this.siteKey = siteKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getVerifyUrl() {
        return verifyUrl;
    }

    public void setVerifyUrl(String verifyUrl) {
        this.verifyUrl = verifyUrl;
    }
}
