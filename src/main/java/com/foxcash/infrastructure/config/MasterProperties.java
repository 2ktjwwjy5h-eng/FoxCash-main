package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do perfil master (Spec 13).
 *
 * <p>Seed do primeiro master (env vars {@code FOXCASH_MASTER_SEED_USERNAME} /
 * {@code FOXCASH_MASTER_SEED_PASSWORD}) e tempo de vida da sessao
 * ({@code foxcash.master.session-ttl-seconds}, default 8h). Em prod o seed nao
 * tem default — sem env vars, o primeiro master nao e criado (documentado).
 */
@ConfigurationProperties("foxcash.master")
public class MasterProperties {

    /** Usuario do primeiro master (seed no boot; sem default em prod). */
    private String seedUsername;

    /** Senha do primeiro master (seed no boot; sem default em prod). */
    private String seedPassword;

    /** Nome exibido do primeiro master (default: "Master"). */
    private String seedName = "Master";

    /** Validade da sessao em segundos (default 8h). */
    private long sessionTtlSeconds = 8 * 60 * 60;

    /** Limite maximo de perfis master (Spec 41 — 5º perfil → 409 too_many_masters). */
    private int maxProfiles = 4;

    public String getSeedUsername() {
        return seedUsername;
    }

    public void setSeedUsername(String seedUsername) {
        this.seedUsername = seedUsername;
    }

    public String getSeedPassword() {
        return seedPassword;
    }

    public void setSeedPassword(String seedPassword) {
        this.seedPassword = seedPassword;
    }

    public String getSeedName() {
        return seedName;
    }

    public void setSeedName(String seedName) {
        this.seedName = seedName;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public int getMaxProfiles() {
        return maxProfiles;
    }

    public void setMaxProfiles(int maxProfiles) {
        this.maxProfiles = maxProfiles;
    }
}
