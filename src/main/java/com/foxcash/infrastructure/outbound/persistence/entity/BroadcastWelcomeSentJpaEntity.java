package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da idempotência do modo WELCOME (Spec 79/D6) — evento de entrada
 * duplicado (Telegram re-envia {@code chat_member}) não dispara a mensagem 2×
 * por {@code (chat_id, user_id)} (UNIQUE na tabela).
 */
@Entity
@Table(name = "broadcast_welcome_sent",
        uniqueConstraints = @UniqueConstraint(name = "uk_welcome_sent_chat_user",
                columnNames = {"chat_id", "user_id"}))
public class BroadcastWelcomeSentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "broadcast_id", nullable = false)
    private UUID broadcastId;

    @Column(name = "chat_id", nullable = false, length = 255)
    private String chatId;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BroadcastWelcomeSentJpaEntity() {
    }

    public BroadcastWelcomeSentJpaEntity(UUID id, UUID broadcastId, String chatId,
                                         String userId, Instant createdAt) {
        this.id = id;
        this.broadcastId = broadcastId;
        this.chatId = chatId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBroadcastId() {
        return broadcastId;
    }

    public String getChatId() {
        return chatId;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
