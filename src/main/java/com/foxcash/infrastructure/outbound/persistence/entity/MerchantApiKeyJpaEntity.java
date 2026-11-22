package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de chave de API gerenciável do lojista (Spec 24) — desacoplada
 * do modelo de dominio. Apenas o hash SHA-256 ({@code keyHash}) e o prefixo
 * sao persistidos; o segredo em claro nunca.
 */
@Entity
@Table(name = "merchant_api_keys")
public class MerchantApiKeyJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(nullable = false, length = 16)
    private String prefix;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 255)
    private String scopes;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantApiKeyJpaEntity() {
    }

    public MerchantApiKeyJpaEntity(UUID id, UUID merchantId, String label, String prefix,
                                   String keyHash, String status, String scopes,
                                   Instant lastUsedAt, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.label = label;
        this.prefix = prefix;
        this.keyHash = keyHash;
        this.status = status;
        this.scopes = scopes;
        this.lastUsedAt = lastUsedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getLabel() {
        return label;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getStatus() {
        return status;
    }

    public String getScopes() {
        return scopes;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
