package com.foxcash.domain.model.master;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sessão do master (Spec 13, ADR-007).
 *
 * <p>Guarda apenas o hash SHA-256 do token opaco ({@code token_hash}) — o token
 * em claro é devolvido no login e nunca persistido. A sessão expira após
 * {@code expires_at} e pode ser revogada ({@code revoked_at}).
 *
 * <p>Spec 69 (step-up MFA): {@code stepped_up_at} registra a re-prova de posse
 * (TOTP/recovery) para ações sensíveis — a sessão fica "elevada" por uma janela
 * configurável (default 15 min). {@link #isSteppedUp(Instant, Duration)} exige
 * a sessão válida E o step-up dentro da janela.
 */
public class MasterSession {

    private final UUID id;
    private final UUID masterId;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Instant revokedAt;
    private Instant steppedUpAt;

    private MasterSession(UUID id, UUID masterId, String tokenHash,
                          Instant createdAt, Instant expiresAt, Instant revokedAt,
                          Instant steppedUpAt) {
        this.id = id;
        this.masterId = masterId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.steppedUpAt = steppedUpAt;
    }

    public static MasterSession issue(UUID masterId, String tokenHash, Instant now, Instant expiresAt) {
        Objects.requireNonNull(masterId, "masterId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        return new MasterSession(UUID.randomUUID(), masterId, tokenHash, now, expiresAt, null, null);
    }

    public static MasterSession restore(UUID id, UUID masterId, String tokenHash,
                                        Instant createdAt, Instant expiresAt, Instant revokedAt) {
        return new MasterSession(id, masterId, tokenHash, createdAt, expiresAt, revokedAt, null);
    }

    /** Restore completo (Spec 69 — F3): inclui o step-up da sessão. */
    public static MasterSession restore(UUID id, UUID masterId, String tokenHash,
                                        Instant createdAt, Instant expiresAt, Instant revokedAt,
                                        Instant steppedUpAt) {
        return new MasterSession(id, masterId, tokenHash, createdAt, expiresAt, revokedAt, steppedUpAt);
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    /** Registra a re-prova de posse (step-up MFA, Spec 69) no instante {@code now}. */
    public void markSteppedUp(Instant now) {
        this.steppedUpAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Válida: não revogada e ainda dentro do prazo de expiração. */
    public boolean isValid(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /**
     * Elevada (step-up) no instante {@code now}? Exige sessão válida E
     * {@code stepped_up_at} presente dentro da janela {@code window}.
     */
    public boolean isSteppedUp(Instant now, Duration window) {
        return isValid(now) && steppedUpAt != null && !now.isAfter(steppedUpAt.plus(window));
    }

    public UUID id() {
        return id;
    }

    public UUID masterId() {
        return masterId;
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

    public Instant steppedUpAt() {
        return steppedUpAt;
    }
}
