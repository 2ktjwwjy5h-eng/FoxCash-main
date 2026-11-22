package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.broadcast.BroadcastLogStatus;
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
 * Entidade JPA do log por destinatário do broadcast (Spec 79). A UNIQUE
 * {@code (broadcast_id, recipient_id)} garante idempotência: replay de log não
 * duplica (padrão {@code bot_funnel_events}, Spec 75).
 */
@Entity
@Table(name = "broadcast_logs",
        uniqueConstraints = @UniqueConstraint(name = "uk_broadcast_log_recipient",
                columnNames = {"broadcast_id", "recipient_id"}))
public class BroadcastLogJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broadcast_id", nullable = false)
    private UUID broadcastId;

    @Column(name = "recipient_id", nullable = false, length = 255)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BroadcastLogStatus status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected BroadcastLogJpaEntity() {
    }

    public BroadcastLogJpaEntity(UUID broadcastId, String recipientId, BroadcastLogStatus status,
                                 String errorMessage, Instant sentAt) {
        this.broadcastId = broadcastId;
        this.recipientId = recipientId;
        this.status = status;
        this.errorMessage = errorMessage;
        this.sentAt = sentAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getBroadcastId() {
        return broadcastId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public BroadcastLogStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
