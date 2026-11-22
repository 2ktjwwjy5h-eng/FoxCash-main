package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA do lead de bot de venda (Spec 75) — desacoplada do modelo de
 * domínio. {@code UNIQUE (bot_id, platform_user_id)} garante a idempotência
 * do registro (replay de update/interação não duplica; o serviço faz no-op).
 *
 * <p>Specs 83/90 (CRM): colunas de perfil ({@code name/username/email/phone}),
 * {@code status} (derivado do funil), {@code handoff_status} (atendimento
 * manual por lead) e {@code updated_at}.
 */
@Entity
@Table(name = "bot_leads", uniqueConstraints = @UniqueConstraint(
        name = "uq_bot_leads_bot_user", columnNames = {"bot_id", "platform_user_id"}))
public class BotLeadJpaEntity {

    @Id
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "platform_user_id", nullable = false, length = 255)
    private String platformUserId;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "utm_source", length = 255)
    private String utmSource;

    @Column(name = "utm_medium", length = 255)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 255)
    private String utmCampaign;

    @Column(name = "utm_content", length = 255)
    private String utmContent;

    @Column(name = "utm_term", length = 255)
    private String utmTerm;

    // ---- CRM (Specs 83/90) ----

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "username", length = 255)
    private String username;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "handoff_status", nullable = false, length = 16)
    private String handoffStatus;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected BotLeadJpaEntity() {
    }

    public BotLeadJpaEntity(UUID id, UUID botId, String platformUserId,
                            Instant firstSeenAt, Instant createdAt,
                            String utmSource, String utmMedium, String utmCampaign,
                            String utmContent, String utmTerm,
                            String name, String username, String email, String phone,
                            String status, String handoffStatus, Instant updatedAt) {
        this.id = id;
        this.botId = botId;
        this.platformUserId = platformUserId;
        this.firstSeenAt = firstSeenAt;
        this.createdAt = createdAt;
        this.utmSource = utmSource;
        this.utmMedium = utmMedium;
        this.utmCampaign = utmCampaign;
        this.utmContent = utmContent;
        this.utmTerm = utmTerm;
        this.name = name;
        this.username = username;
        this.email = email;
        this.phone = phone;
        this.status = status;
        this.handoffStatus = handoffStatus;
        this.updatedAt = updatedAt;
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

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUtmSource() { return utmSource; }
    public String getUtmMedium() { return utmMedium; }
    public String getUtmCampaign() { return utmCampaign; }
    public String getUtmContent() { return utmContent; }
    public String getUtmTerm() { return utmTerm; }

    public String getName() { return name; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getStatus() { return status; }
    public String getHandoffStatus() { return handoffStatus; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ---- Atualização in-place (Specs 83/90 — status derivado do funil e
    // handoff por lead preservam o id/created_at da linha) ----

    public void setStatus(String status) { this.status = status; }
    public void setHandoffStatus(String handoffStatus) { this.handoffStatus = handoffStatus; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
