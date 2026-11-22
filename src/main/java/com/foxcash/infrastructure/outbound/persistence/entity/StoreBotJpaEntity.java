package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.infrastructure.security.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do registro de bot de venda (Spec 74) — desacoplada do modelo de
 * domínio. O {@code token} é **criptografado em repouso** (CryptoConverter,
 * AES-256/GCM — nunca em claro no banco); o {@code token_hash} (SHA-256 hex)
 * permite lookup por igualdade (o ciphertext é não-determinístico por IV). O
 * {@code webhook_secret} nunca é persistido — só o {@code webhook_secret_hash}.
 */
@Entity
@Table(name = "store_bots")
public class StoreBotJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "profile_id")
    private UUID profileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BotPlatform platform;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String token;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "webhook_secret_hash", nullable = false, unique = true, length = 64)
    private String webhookSecretHash;

    /**
     * SHA-256 hex do secret token independente do header do Telegram
     * ({@code X-Telegram-Bot-Api-Secret-Token}, auditoria 2026-08-18).
     * Nullable: bots existentes (backfill) têm o hash do url_secret até a
     * re-integração gerar um token novo.
     */
    @Column(name = "webhook_secret_token_hash", length = 64)
    private String webhookSecretTokenHash;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(length = 255)
    private String username;

    @Column(name = "application_id", length = 64)
    private String applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BotStatus status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "last_health_check_at")
    private Instant lastHealthCheckAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoreBotJpaEntity() {
    }

    /** Overload sem o secret token do header (compatibilidade com callers antigos). */
    public StoreBotJpaEntity(UUID id, UUID merchantId, BotPlatform platform, String token,
                             String tokenHash, String webhookSecretHash, String displayName,
                             String username, String applicationId, BotStatus status,
                             String errorMessage, Instant lastHealthCheckAt, Instant deletedAt,
                             Instant createdAt, Instant updatedAt) {
        this(id, merchantId, null, platform, token, tokenHash, webhookSecretHash, null,
                displayName, username, applicationId, status, errorMessage, lastHealthCheckAt,
                deletedAt, createdAt, updatedAt);
    }

    /**
     * Overload com o secret token do header, sem perfil de operação
     * (auditoria 2026-08-18).
     */
    public StoreBotJpaEntity(UUID id, UUID merchantId, BotPlatform platform, String token,
                             String tokenHash, String webhookSecretHash, String webhookSecretTokenHash,
                             String displayName, String username, String applicationId, BotStatus status,
                             String errorMessage, Instant lastHealthCheckAt, Instant deletedAt,
                             Instant createdAt, Instant updatedAt) {
        this(id, merchantId, null, platform, token, tokenHash, webhookSecretHash, webhookSecretTokenHash,
                displayName, username, applicationId, status, errorMessage, lastHealthCheckAt,
                deletedAt, createdAt, updatedAt);
    }

    /** Com perfil de operação (Spec 122 — frota por perfil). */
    public StoreBotJpaEntity(UUID id, UUID merchantId, UUID profileId, BotPlatform platform, String token,
                             String tokenHash, String webhookSecretHash, String displayName,
                             String username, String applicationId, BotStatus status,
                             String errorMessage, Instant lastHealthCheckAt, Instant deletedAt,
                             Instant createdAt, Instant updatedAt) {
        this(id, merchantId, profileId, platform, token, tokenHash, webhookSecretHash, null,
                displayName, username, applicationId, status, errorMessage, lastHealthCheckAt,
                deletedAt, createdAt, updatedAt);
    }

    /**
     * Construtor completo (auditoria 2026-08-18): inclui o hash do secret token
     * independente do header do Telegram.
     */
    public StoreBotJpaEntity(UUID id, UUID merchantId, UUID profileId, BotPlatform platform, String token,
                             String tokenHash, String webhookSecretHash, String webhookSecretTokenHash,
                             String displayName, String username, String applicationId, BotStatus status,
                             String errorMessage, Instant lastHealthCheckAt, Instant deletedAt,
                             Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.profileId = profileId;
        this.platform = platform;
        this.token = token;
        this.tokenHash = tokenHash;
        this.webhookSecretHash = webhookSecretHash;
        this.webhookSecretTokenHash = webhookSecretTokenHash;
        this.displayName = displayName;
        this.username = username;
        this.applicationId = applicationId;
        this.status = status;
        this.errorMessage = errorMessage;
        this.lastHealthCheckAt = lastHealthCheckAt;
        this.deletedAt = deletedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    /** Perfil de operação do bot (Spec 122 — frota por perfil); null = default. */
    public UUID getProfileId() {
        return profileId;
    }

    public BotPlatform getPlatform() {
        return platform;
    }

    /** Token em claro em memória (CryptoConverter descriptografa na leitura). */
    public String getToken() {
        return token;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getWebhookSecretHash() {
        return webhookSecretHash;
    }

    public String getWebhookSecretTokenHash() {
        return webhookSecretTokenHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getUsername() {
        return username;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public BotStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getLastHealthCheckAt() {
        return lastHealthCheckAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
