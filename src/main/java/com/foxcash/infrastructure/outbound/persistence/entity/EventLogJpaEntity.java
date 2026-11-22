package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.tracking.EventLogStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do log de disparo de tracking (Spec 81/D2.1) — idempotente por
 * {@code UNIQUE (profile_id, event_id)}.
 */
@Entity
@Table(name = "event_logs", uniqueConstraints = @UniqueConstraint(
        name = "uq_event_logs_profile_event", columnNames = {"profile_id", "event_id"}))
public class EventLogJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventLogStatus status;

    @Column(name = "response_payload", columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EventLogJpaEntity() {
    }

    public EventLogJpaEntity(Long id, UUID profileId, String eventType, UUID eventId,
                             EventLogStatus status, String responsePayload, Instant createdAt) {
        this.id = id;
        this.profileId = profileId;
        this.eventType = eventType;
        this.eventId = eventId;
        this.status = status;
        this.responsePayload = responsePayload;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public UUID getProfileId() { return profileId; }
    public String getEventType() { return eventType; }
    public UUID getEventId() { return eventId; }
    public EventLogStatus getStatus() { return status; }
    public String getResponsePayload() { return responsePayload; }
    public Instant getCreatedAt() { return createdAt; }

    /** Upsert idempotente (Spec 81): re-grava status/payload do retry. */
    public void updateResult(EventLogStatus newStatus, String newPayload) {
        this.status = newStatus;
        this.responsePayload = newPayload;
    }
}
