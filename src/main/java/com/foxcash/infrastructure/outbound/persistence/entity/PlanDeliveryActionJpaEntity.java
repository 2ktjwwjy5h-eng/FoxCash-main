package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.plan.DeliveryActionType;
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
 * Entidade JPA da ação de entrega configurada no plano (Spec 49). O
 * {@code payload} (JSON tipado — roleId/text/url/groupId/fileUrl) é
 * **criptografado em repouso** (CryptoConverter — nunca em claro no banco).
 */
@Entity
@Table(name = "plan_delivery_actions")
public class PlanDeliveryActionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private DeliveryActionType actionType;

    @Convert(converter = CryptoConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlanDeliveryActionJpaEntity() {
    }

    public PlanDeliveryActionJpaEntity(UUID id, UUID planId, int seq,
                                       DeliveryActionType actionType, String payload,
                                       Instant createdAt) {
        this.id = id;
        this.planId = planId;
        this.seq = seq;
        this.actionType = actionType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public int getSeq() {
        return seq;
    }

    public DeliveryActionType getActionType() {
        return actionType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
