package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de sessao do master (Spec 13). Guarda apenas o hash SHA-256 do
 * token opaco — o token em claro nunca e persistido. Spec 69: {@code
 * stepped_up_at} (step-up MFA em acoes sensiveis — nullable).
 */
@Entity
@Table(name = "master_sessions")
public class MasterSessionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "master_id", nullable = false)
    private UUID masterId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Spec 69: re-prova de posse (TOTP/recovery) para acoes sensíveis — nullable. */
    @Column(name = "stepped_up_at")
    private Instant steppedUpAt;

    protected MasterSessionJpaEntity() {
    }

    public MasterSessionJpaEntity(UUID id, UUID masterId, String tokenHash,
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

    public UUID getId() {
        return id;
    }

    public UUID getMasterId() {
        return masterId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getSteppedUpAt() {
        return steppedUpAt;
    }
}
