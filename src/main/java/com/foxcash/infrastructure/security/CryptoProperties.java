package com.foxcash.infrastructure.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao da criptografia (foxcash.crypto.secret).
 * A chave vem de FOXCASH_CRYPTO_SECRET em producao; o default e apenas dev.
 *
 * <p>Auditoria 2026-08-05 (B-A2): a chave AES e derivada via SHA-256 do segredo
 * ({@link CryptoConverter}) — correta quando o segredo tem entropia suficiente.
 * Fail-fast aqui garante segredo longo o bastante (>= 32 chars) e sem
 * placeholder nao-resolvido no boot.
 */
@ConfigurationProperties(prefix = "foxcash.crypto")
public class CryptoProperties {

    /** Entropia minima do segredo (32 chars ≈ 128+ bits quando aleatorio). */
    static final int MIN_SECRET_LENGTH = 32;

    private String secret;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("foxcash.crypto.secret e obrigatorio (FOXCASH_CRYPTO_SECRET em producao)");
        }
        if (secret.contains("${")) {
            // Placeholder nao-resolvido (env var ausente) — fail-fast em vez de
            // derivar chave de um valor conhecido.
            throw new IllegalStateException(
                    "foxcash.crypto.secret nao foi resolvido (FOXCASH_CRYPTO_SECRET ausente): " + secret);
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "foxcash.crypto.secret muito curto (minimo " + MIN_SECRET_LENGTH
                            + " caracteres — entropia insuficiente para derivar a chave AES-256)");
        }
    }
}
