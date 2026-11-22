package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da loja do vendedor (Spec 48) — desacoplada do modelo de
 * dominio. {@code social_links} e uma string JSON (array de {label, url}) — o
 * mapeamento de/para o dominio acontece no {@code StoreMapper}.
 */
@Entity
@Table(name = "stores")
public class StoreJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private UUID merchantId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "banner_url", length = 500)
    private String bannerUrl;

    @Column(length = 500)
    private String description;

    @Column(name = "social_links", columnDefinition = "TEXT")
    private String socialLinks;

    @Column(length = 9)
    private String color;

    @Column(length = 10)
    private String emoji;

    @Column(length = 120)
    private String title;

    @Column(length = 500)
    private String footer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreJpaEntity() {
    }

    public StoreJpaEntity(UUID id, UUID merchantId, String name, String logoUrl, String bannerUrl,
                          String description, String socialLinks, String color, String emoji,
                          String title, String footer, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.logoUrl = logoUrl;
        this.bannerUrl = bannerUrl;
        this.description = description;
        this.socialLinks = socialLinks;
        this.color = color;
        this.emoji = emoji;
        this.title = title;
        this.footer = footer;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getName() {
        return name;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public String getBannerUrl() {
        return bannerUrl;
    }

    public String getDescription() {
        return description;
    }

    public String getSocialLinks() {
        return socialLinks;
    }

    public String getColor() {
        return color;
    }

    public String getEmoji() {
        return emoji;
    }

    public String getTitle() {
        return title;
    }

    public String getFooter() {
        return footer;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
