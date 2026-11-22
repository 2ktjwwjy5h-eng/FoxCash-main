package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.tracking.TrackingStatus;
import com.foxcash.infrastructure.security.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do perfil de tracking (Spec 81) — tokens de provedores
 * **criptografados em repouso** (CryptoConverter AES-256/GCM, padrão Spec 74);
 * nunca em claro no banco nem em resposta HTTP.
 */
@Entity
@Table(name = "tracking_profiles")
public class TrackingProfileJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TrackingStatus status;

    @Column(name = "facebook_pixel_id", length = 255)
    private String facebookPixelId;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "facebook_access_token_encrypted", columnDefinition = "TEXT")
    private String facebookAccessToken;

    @Column(name = "tiktok_pixel_id", length = 255)
    private String tiktokPixelId;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "tiktok_access_token_encrypted", columnDefinition = "TEXT")
    private String tiktokAccessToken;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "utmify_token_encrypted", columnDefinition = "TEXT")
    private String utmifyToken;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "otimizey_token_encrypted", columnDefinition = "TEXT")
    private String otimizeyToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TrackingProfileJpaEntity() {
    }

    public TrackingProfileJpaEntity(UUID id, UUID merchantId, String name, TrackingStatus status,
                                    String facebookPixelId, String facebookAccessToken,
                                    String tiktokPixelId, String tiktokAccessToken,
                                    String utmifyToken, String otimizeyToken,
                                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.status = status;
        this.facebookPixelId = facebookPixelId;
        this.facebookAccessToken = facebookAccessToken;
        this.tiktokPixelId = tiktokPixelId;
        this.tiktokAccessToken = tiktokAccessToken;
        this.utmifyToken = utmifyToken;
        this.otimizeyToken = otimizeyToken;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getName() { return name; }
    public TrackingStatus getStatus() { return status; }
    public String getFacebookPixelId() { return facebookPixelId; }
    public String getFacebookAccessToken() { return facebookAccessToken; }
    public String getTiktokPixelId() { return tiktokPixelId; }
    public String getTiktokAccessToken() { return tiktokAccessToken; }
    public String getUtmifyToken() { return utmifyToken; }
    public String getOtimizeyToken() { return otimizeyToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
