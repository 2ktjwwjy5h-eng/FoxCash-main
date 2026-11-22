package com.foxcash.domain.model.merchant;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sessão do portal do lojista (Spec 20).
 *
 * <p>Guarda apenas o hash SHA-256 do token opaco ({@code token_hash}) — o token
 * em claro é devolvido no login e nunca persistido. A sessão expira após
 * {@code expires_at} e pode ser revogada ({@code revoked_at}). Mesmo padrão de
 * {@code MasterSession} (Spec 13).
 */
public class MerchantSession {

    private final UUID id;
    private final UUID merchantId;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Instant revokedAt;

    private MerchantSession(UUID id, UUID merchantId, String tokenHash,
                            Instant createdAt, Instant expiresAt, Instant revokedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public static MerchantSession issue(UUID merchantId, String tokenHash, Instant now, Instant expiresAt) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        return new MerchantSession(UUID.randomUUID(), merchantId, tokenHash, now, expiresAt, null);
    }

    public static MerchantSession restore(UUID id, UUID merchantId, String tokenHash,
                                          Instant createdAt, Instant expiresAt, Instant revokedAt) {
        return new MerchantSession(id, merchantId, tokenHash, createdAt, expiresAt, revokedAt);
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    /** Válida: não revogada e ainda dentro do prazo de expiração. */
    public boolean isValid(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
