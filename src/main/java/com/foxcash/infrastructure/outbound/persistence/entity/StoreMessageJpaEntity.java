package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.store.StoreMessageEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da mensagem automática da loja (Spec 51) — desacoplada do
 * modelo de domínio. O {@code event} é único por loja (unique
 * {@code (store_id, event)} — upsert do portal). O {@code text} pode conter
 * link privado — em claro apenas em memória/resposta ao dono (a trilha/logs
 * registram apenas o event; logs já mascaram a chave "text").
 */
@Entity
@Table(name = "store_messages")
public class StoreMessageJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private StoreMessageEvent event;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreMessageJpaEntity() {
    }

    public StoreMessageJpaEntity(UUID id, UUID storeId, StoreMessageEvent event, String text,
                                 boolean active, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.storeId = storeId;
        this.event = event;
        this.text = text;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public StoreMessageEvent getEvent() {
        return event;
    }

    public String getText() {
        return text;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
