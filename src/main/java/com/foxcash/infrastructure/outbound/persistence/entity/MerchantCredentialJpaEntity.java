package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de credencial do portal do lojista (Spec 20). Guarda apenas o
 * hash BCrypt da senha — a senha em claro nunca e persistida.
 */
@Entity
@Table(name = "merchant_credentials")
public class MerchantCredentialJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private UUID merchantId;

    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    // Auditoria 2026-08-05 (B-06): lockout por conta no login do lojista.
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected MerchantCredentialJpaEntity() {
    }

    public MerchantCredentialJpaEntity(UUID id, UUID merchantId, String passwordHash,
                                       Instant createdAt, Instant updatedAt, Instant lastUsedAt) {
        this(id, merchantId, passwordHash, createdAt, updatedAt, lastUsedAt, 0, null);
    }

    public MerchantCredentialJpaEntity(UUID id, UUID merchantId, String passwordHash,
                                       Instant createdAt, Instant updatedAt, Instant lastUsedAt,
                                       int failedLoginAttempts, Instant lockedUntil) {
        this.id = id;
        this.merchantId = merchantId;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastUsedAt = lastUsedAt;
        this.failedLoginAttempts = failedLoginAttempts;
        this.lockedUntil = lockedUntil;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
