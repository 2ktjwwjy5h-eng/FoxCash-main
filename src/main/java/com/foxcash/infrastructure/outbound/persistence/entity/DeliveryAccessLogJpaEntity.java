package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do log de acesso ao conteúdo entregue (Spec 49+, 2026-08-06).
 * Prova de que o comprador usou o produto — evidência em disputa de chargeback.
 */
@Entity
@Table(name = "plan_delivery_access_logs")
public class DeliveryAccessLogJpaEntity {

    @Id
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    @Column(length = 45)
    private String ip;

    @Column(length = 255)
    private String device;

    @Column(length = 255)
    private String note;

    protected DeliveryAccessLogJpaEntity() {
    }

    public DeliveryAccessLogJpaEntity(UUID id, UUID deliveryId, Instant accessedAt,
                                      String ip, String device, String note) {
        this.id = id;
        this.deliveryId = deliveryId;
        this.accessedAt = accessedAt;
        this.ip = ip;
        this.device = device;
        this.note = note;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public Instant getAccessedAt() {
        return accessedAt;
    }

    public String getIp() {
        return ip;
    }

    public String getDevice() {
        return device;
    }

    public String getNote() {
        return note;
    }
}
