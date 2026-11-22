package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do outbox de notificacoes (Spec 7/35).
 * status: 'PENDING' (aguardando entrega) | 'SENT' (entregue) | 'FAILED'
 * (esgotou o max de tentativas). Colunas de retry: attempts/last_error/
 * next_attempt_at (Spec 35).
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutboxJpaEntity {

    @Id
    private UUID id;

    /** Nullable (Spec 53): eventos de PLATAFORMA (ex.: JOB_FAILED) nao tem merchant. */
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "entity_type", length = 64)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    protected NotificationOutboxJpaEntity() {
    }

    public NotificationOutboxJpaEntity(UUID id, UUID merchantId, String type, String payload,
                                       String status, Instant createdAt, Instant sentAt) {
        this(id, merchantId, type, payload, status, createdAt, sentAt, 0, null, null, null, null);
    }

    public NotificationOutboxJpaEntity(UUID id, UUID merchantId, String type, String payload,
                                       String status, Instant createdAt, Instant sentAt,
                                       int attempts, String lastError, Instant nextAttemptAt) {
        this(id, merchantId, type, payload, status, createdAt, sentAt, attempts, lastError,
                nextAttemptAt, null, null);
    }

    /** Construtor completo com a referência de origem (Spec 125 — trace). */
    public NotificationOutboxJpaEntity(UUID id, UUID merchantId, String type, String payload,
                                       String status, Instant createdAt, Instant sentAt,
                                       int attempts, String lastError, Instant nextAttemptAt,
                                       String entityType, UUID entityId) {
        this.id = id;
        this.merchantId = merchantId;
        this.type = type;
        this.payload = payload;
        this.status = status;
        this.createdAt = createdAt;
        this.sentAt = sentAt;
        this.attempts = attempts;
        this.lastError = lastError;
        this.nextAttemptAt = nextAttemptAt;
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }
}
