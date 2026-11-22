package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.infrastructure.security.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do webhook de saida do lojista (Spec 35).
 * {@code secret} criptografado em repouso (AES-256/GCM, prefixo {@code v1:}).
 * {@code events} armazenado como CSV de {@code WebhookEventType}.
 * status: ACTIVE | INACTIVE.
 */
@Entity
@Table(name = "merchant_webhooks")
public class MerchantWebhookJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 500)
    private String url;

    @Convert(converter = CryptoConverter.class)
    @Column(nullable = false, length = 255)
    private String secret;

    @Column(nullable = false, length = 255)
    private String events;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "signature_version", nullable = false, length = 10)
    private String signatureVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantWebhookJpaEntity() {
    }

    public MerchantWebhookJpaEntity(UUID id, UUID merchantId, String url, String secret,
                                    String events, String status, String signatureVersion,
                                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.url = url;
        this.secret = secret;
        this.events = events;
        this.status = status;
        this.signatureVersion = signatureVersion;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getUrl() {
        return url;
    }

    public String getSecret() {
        return secret;
    }

    public String getEvents() {
        return events;
    }

    public String getStatus() {
        return status;
    }

    public String getSignatureVersion() {
        return signatureVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
