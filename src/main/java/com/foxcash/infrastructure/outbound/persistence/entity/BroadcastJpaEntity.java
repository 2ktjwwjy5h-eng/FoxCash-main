package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.broadcast.BroadcastMediaType;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do broadcast (Spec 79) — desacoplada do modelo de domínio.
 * O {@code buttons_json} (JSONB) é persistido como JSON cru (String) — o parse
 * e a validação acontecem no value object de domínio {@code BroadcastButtons}
 * (D8); aqui só o transporte.
 */
@Entity
@Table(name = "broadcasts")
public class BroadcastJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_bot_id", nullable = false)
    private UUID storeBotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BroadcastMode mode;

    @Column(name = "target_chat_id", length = 255)
    private String targetChatId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "media_url", length = 500)
    private String mediaUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", length = 16)
    private BroadcastMediaType mediaType;

    @Column(name = "media_file_id", length = 255)
    private String mediaFileId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "buttons_json", columnDefinition = "JSONB")
    private String buttonsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BroadcastStatus status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "interval_seconds")
    private Integer intervalSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BroadcastJpaEntity() {
    }

    public BroadcastJpaEntity(UUID id, UUID storeBotId, BroadcastMode mode, String targetChatId,
                              String content, String mediaUrl, BroadcastMediaType mediaType,
                              String mediaFileId, String buttonsJson, BroadcastStatus status,
                              String errorMessage, Instant scheduledAt, Integer intervalSeconds,
                              Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.storeBotId = storeBotId;
        this.mode = mode;
        this.targetChatId = targetChatId;
        this.content = content;
        this.mediaUrl = mediaUrl;
        this.mediaType = mediaType;
        this.mediaFileId = mediaFileId;
        this.buttonsJson = buttonsJson;
        this.status = status;
        this.errorMessage = errorMessage;
        this.scheduledAt = scheduledAt;
        this.intervalSeconds = intervalSeconds;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreBotId() {
        return storeBotId;
    }

    public BroadcastMode getMode() {
        return mode;
    }

    public String getTargetChatId() {
        return targetChatId;
    }

    public String getContent() {
        return content;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public BroadcastMediaType getMediaType() {
        return mediaType;
    }

    public String getMediaFileId() {
        return mediaFileId;
    }

    public String getButtonsJson() {
        return buttonsJson;
    }

    public BroadcastStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
