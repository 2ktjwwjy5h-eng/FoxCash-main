package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da trilha unificada de atividades (Spec 41) — relatório do admin.
 * Append-only: nenhum update/delete.
 */
@Entity
@Table(name = "platform_activity_log")
public class PlatformActivityJpaEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private PlatformActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private PlatformAction action;

    @Column(name = "entity_type", length = 32)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(length = 45)
    private String ip;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlatformActivityJpaEntity() {
    }

    public PlatformActivityJpaEntity(UUID id, PlatformActorType actorType, UUID actorId,
                                     PlatformAction action, String entityType, UUID entityId,
                                     UUID merchantId, String details, String ip, Instant createdAt) {
        this.id = id;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.merchantId = merchantId;
        this.details = details;
        this.ip = ip;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public PlatformActorType getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public PlatformAction getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getDetails() {
        return details;
    }

    public String getIp() {
        return ip;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
