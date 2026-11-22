package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.store.ScheduledMessageAudience;
import com.foxcash.domain.model.store.ScheduledMessageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da mensagem programada da loja (Spec 51) — desacoplada do
 * modelo de domínio. One-shot ({@code scheduledAt}) OU recorrente
 * ({@code cron} 6 campos — validado no domínio). O {@code text} pode conter
 * link privado — nunca em claro na trilha/logs.
 */
@Entity
@Table(name = "store_scheduled_messages")
public class ScheduledMessageJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(length = 100)
    private String cron;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduledMessageAudience audience;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduledMessageStatus status;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ScheduledMessageJpaEntity() {
    }

    public ScheduledMessageJpaEntity(UUID id, UUID storeId, String text, Instant scheduledAt,
                                     String cron, ScheduledMessageAudience audience,
                                     ScheduledMessageStatus status, Instant sentAt, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.text = text;
        this.scheduledAt = scheduledAt;
        this.cron = cron;
        this.audience = audience;
        this.status = status;
        this.sentAt = sentAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getText() {
        return text;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public String getCron() {
        return cron;
    }

    public ScheduledMessageAudience getAudience() {
        return audience;
    }

    public ScheduledMessageStatus getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
