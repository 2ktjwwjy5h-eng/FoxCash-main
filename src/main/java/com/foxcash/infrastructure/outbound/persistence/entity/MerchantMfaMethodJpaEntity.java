package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do registro de método de MFA do lojista no portal (Spec 94) —
 * modelagem preparada para múltiplos métodos (type = TOTP, futuro WEBAUTHN).
 * Espelho do {@code MasterMfaMethodJpaEntity} (Spec 44).
 */
@Entity
@Table(name = "merchant_mfa_methods")
public class MerchantMfaMethodJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

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

    protected MerchantMfaMethodJpaEntity() {
    }

    public MerchantMfaMethodJpaEntity(UUID id, UUID merchantId, String type, String status,
                                      Instant createdAt, Instant activatedAt, Instant expiresAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.type = type;
        this.status = status;
        this.createdAt = createdAt;
        this.activatedAt = activatedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
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
