package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do registro de método de MFA do master (Spec 44) — modelagem
 * preparada para múltiplos métodos (type = TOTP, futuro WEBAUTHN).
 */
@Entity
@Table(name = "master_mfa_methods")
public class MasterMfaMethodJpaEntity {

    @Id
    private UUID id;

    @Column(name = "master_id", nullable = false)
    private UUID masterId;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected MasterMfaMethodJpaEntity() {
    }

    public MasterMfaMethodJpaEntity(UUID id, UUID masterId, String type, String status,
                                    Instant createdAt, Instant activatedAt, Instant expiresAt) {
        this.id = id;
        this.masterId = masterId;
        this.type = type;
        this.status = status;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMasterId() {
        return masterId;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
