package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do código de recuperação do MFA do lojista no portal (Spec 94) —
 * SHA-256 single-use (o código em claro nunca é persistido). Espelho do
 * {@code MasterRecoveryCodeJpaEntity} (Spec 44).
 */
@Entity
@Table(name = "merchant_recovery_codes")
public class MerchantRecoveryCodeJpaEntity {

    @Id
    private UUID id;

    @Column(name = "method_id", nullable = false)
    private UUID methodId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantRecoveryCodeJpaEntity() {
    }

    public MerchantRecoveryCodeJpaEntity(UUID id, UUID methodId, String codeHash,
                                         Instant usedAt, Instant createdAt) {
        this.id = id;
        this.methodId = methodId;
        this.codeHash = codeHash;
        this.usedAt = usedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMethodId() {
        return methodId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
