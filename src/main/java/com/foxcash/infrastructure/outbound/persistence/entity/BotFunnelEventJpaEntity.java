package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.botfunnel.FunnelEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do evento de funil por bot (Spec 75) — trilha append-only
 * {@code lead → checkout_started → paid}. {@code transaction_id} é NULL para
 * {@code lead} e preenchido para {@code checkout_started}/{@code paid}.
 */
@Entity
@Table(name = "bot_funnel_events")
public class BotFunnelEventJpaEntity {

    @Id
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "platform_user_id", nullable = false, length = 255)
    private String platformUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FunnelEvent event;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected BotFunnelEventJpaEntity() {
    }

    public BotFunnelEventJpaEntity(UUID id, UUID botId, String platformUserId,
                                   FunnelEvent event, UUID transactionId, Instant occurredAt) {
        this.id = id;
        this.botId = botId;
        this.platformUserId = platformUserId;
        this.event = event;
        this.transactionId = transactionId;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBotId() {
        return botId;
    }

    public String getPlatformUserId() {
        return platformUserId;
    }

    public FunnelEvent getEvent() {
        return event;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
