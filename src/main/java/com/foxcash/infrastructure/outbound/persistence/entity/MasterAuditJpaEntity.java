package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.audit.MasterAuditAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da trilha de auditoria do master — desacoplada do modelo de
 * dominio (Spec 37). Append-only: a aplicacao apenas insere.
 */
@Entity
@Table(name = "master_audit_log")
public class MasterAuditJpaEntity {

    @Id
    private UUID id;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MasterAuditAction action;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "reference_type", length = 20)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(length = 255)
    private String reason;

    @Column(length = 45)
    private String ip;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MasterAuditJpaEntity() {
    }

    public MasterAuditJpaEntity(UUID id, UUID actorId, MasterAuditAction action,
                                UUID merchantId, String referenceType, UUID referenceId,
                                String beforeJson, String afterJson, String reason,
                                String ip, Instant createdAt) {
        this.id = id;
        this.actorId = actorId;
        this.action = action;
        this.merchantId = merchantId;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.reason = reason;
        this.ip = ip;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public MasterAuditAction getAction() {
        return action;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public String getReason() {
        return reason;
    }

    public String getIp() {
        return ip;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
