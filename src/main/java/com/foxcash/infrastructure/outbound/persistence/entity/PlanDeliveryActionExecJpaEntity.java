package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.delivery.DeliveryActionStatus;
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
 * Entidade JPA da ação de entrega em EXECUÇÃO (Spec 49) — snapshot da ação do
 * plano na data do pagamento. O {@code payload_snapshot} é **criptografado em
 * repouso** (CryptoConverter).
 */
@Entity
@Table(name = "plan_delivery_actions_exec")
public class PlanDeliveryActionExecJpaEntity {

    @Id
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private DeliveryActionType actionType;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "payload_snapshot", nullable = false, columnDefinition = "TEXT")
    private String payloadSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryActionStatus status;

    @Column(length = 255)
    private String note;

    @Column(name = "executed_at")
    private Instant executedAt;

    protected PlanDeliveryActionExecJpaEntity() {
    }

    public PlanDeliveryActionExecJpaEntity(UUID id, UUID deliveryId, int seq,
                                           DeliveryActionType actionType, String payloadSnapshot,
                                           DeliveryActionStatus status, String note, Instant executedAt) {
        this.id = id;
        this.deliveryId = deliveryId;
        this.seq = seq;
        this.actionType = actionType;
        this.payloadSnapshot = payloadSnapshot;
        this.status = status;
        this.note = note;
        this.executedAt = executedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public int getSeq() {
        return seq;
    }

    public DeliveryActionType getActionType() {
        return actionType;
    }

    public String getPayloadSnapshot() {
        return payloadSnapshot;
    }

    public DeliveryActionStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
