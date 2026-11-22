package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.profile.ProfileScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do perfil de operação do lojista (Spec 122) — desacoplada do
 * modelo de domínio. {@code gateway_route} NULL/PADRAO = roteamento por risco
 * (Spec 91); {@code scope} (Spec 139 — V76) classifica a operação; {@code deleted_at}
 * = soft-delete (padrão store_bots).
 */
@Entity
@Table(name = "merchant_profiles")
public class MerchantProfileJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "gateway_route", length = 32)
    private String gatewayRoute;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProfileScope scope;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantProfileJpaEntity() {
    }

    public MerchantProfileJpaEntity(UUID id, UUID merchantId, String name, String gatewayRoute,
                                    ProfileScope scope, boolean isDefault, int sortOrder,
                                    Instant deletedAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.gatewayRoute = gatewayRoute;
        this.scope = scope == null ? ProfileScope.GENERAL : scope;
        this.isDefault = isDefault;
        this.sortOrder = sortOrder;
        this.deletedAt = deletedAt;
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

    public String getGatewayRoute() {
        return gatewayRoute;
    }

    public ProfileScope getScope() {
        return scope;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
