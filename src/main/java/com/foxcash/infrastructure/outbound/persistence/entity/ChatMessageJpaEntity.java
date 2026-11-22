package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA da mensagem do chat do CRM (Spec 83) — histórico por lead com
 * remetente {@code CLIENT/ADMIN/BOT_AUTO}; índice {@code (lead_id, created_at)}
 * para o histórico paginado do Live Chat.
 */
@Entity
@Table(name = "chat_messages", indexes = @Index(
        name = "ix_chat_messages_lead", columnList = "lead_id, created_at"))
public class ChatMessageJpaEntity {

    @Id
    private UUID id;

    @Column(name = "lead_id", nullable = false)
    private UUID leadId;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "sender_type", nullable = false, length = 16)
    private String senderType;

    @Column(name = "text_content")
    private String textContent;

    @Column(name = "media_url", length = 500)
    private String mediaUrl;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatMessageJpaEntity() {
    }

    public ChatMessageJpaEntity(UUID id, UUID leadId, UUID botId, String senderType,
                                String textContent, String mediaUrl, boolean read, Instant createdAt) {
        this.id = id;
        this.leadId = leadId;
        this.botId = botId;
        this.senderType = senderType;
        this.textContent = textContent;
        this.mediaUrl = mediaUrl;
        this.read = read;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getLeadId() { return leadId; }
    public UUID getBotId() { return botId; }
    public String getSenderType() { return senderType; }
    public String getTextContent() { return textContent; }
    public String getMediaUrl() { return mediaUrl; }
    public boolean isRead() { return read; }
    public Instant getCreatedAt() { return createdAt; }
}
