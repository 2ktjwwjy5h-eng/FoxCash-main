package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.store.StoreButtonAction;
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
 * Entidade JPA do botão do menu da loja (Spec 50) — desacoplada do modelo de
 * domínio. O {@code payload} (JSON tipado — mediaUrls[]/text/url+label) é
 * **criptografado em repouso** (CryptoConverter — nunca em claro no banco;
 * pode conter link privado/canal VIP).
 */
@Entity
@Table(name = "store_buttons")
public class StoreButtonJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false, length = 80)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StoreButtonAction action;

    @Convert(converter = CryptoConverter.class)
    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StoreButtonJpaEntity() {
    }

    public StoreButtonJpaEntity(UUID id, UUID storeId, String label, StoreButtonAction action,
                                String payload, int position, boolean active, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.label = label;
        this.action = action;
        this.payload = payload;
        this.position = position;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getLabel() {
        return label;
    }

    public StoreButtonAction getAction() {
        return action;
    }

    public String getPayload() {
        return payload;
    }

    public int getPosition() {
        return position;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
