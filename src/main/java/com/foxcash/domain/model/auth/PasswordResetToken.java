package com.foxcash.domain.model.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Token one-time de recuperação de senha do lojista (Spec 40).
 *
 * <p>Guarda apenas o hash SHA-256 do token em claro ({@code rst_<32 hex>}) —
 * o texto claro é devolvido no {@code forgot-password} (só em dev) e nunca
 * persistido. O token expira após {@code expiresAt} e é **single-use**
 * ({@code usedAt != null} após o {@code reset-password}).
 */
public class PasswordResetToken {

    private final UUID id;
    private final UUID merchantId;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Instant usedAt;

    private PasswordResetToken(UUID id, UUID merchantId, String tokenHash,
                               Instant createdAt, Instant expiresAt, Instant usedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.usedAt = usedAt;
    }

    /**
     * Emite um novo token para o lojista com validade {@code ttl} a partir de {@code now}.
     *
     * @param merchantId id do lojista dono do token
     * @param tokenHash  hash SHA-256 do token em claro (calculado na infraestrutura)
     * @param now        instante da emissão
     * @param ttl        validade do token (ex.: 30min)
     */
    public static PasswordResetToken issue(UUID merchantId, String tokenHash, Instant now, Duration ttl) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        return new PasswordResetToken(UUID.randomUUID(), merchantId, tokenHash, now, now.plus(ttl), null);
    }

    public static PasswordResetToken restore(UUID id, UUID merchantId, String tokenHash,
                                             Instant createdAt, Instant expiresAt, Instant usedAt) {
        return new PasswordResetToken(id, merchantId, tokenHash, createdAt, expiresAt, usedAt);
    }

    /** Válido: não usado e ainda dentro do prazo de expiração. */
    public boolean isValid(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    /** Marca o token como usado (single-use — Spec 40). */
    public void use(Instant now) {
        this.usedAt = now;
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

    public Instant usedAt() {
        return usedAt;
    }
}
