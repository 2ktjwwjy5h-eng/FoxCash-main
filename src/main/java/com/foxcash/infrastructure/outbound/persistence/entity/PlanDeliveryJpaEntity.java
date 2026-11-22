package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.model.plan.Platform;
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
 * Entidade JPA da entrega do plano (Spec 49). O destinatário
 * ({@code platform_user_id} — ID do comprador na plataforma) é
 * **criptografado em repouso** (CryptoConverter): o backend é a única fonte de
 * verdade e os bots são stateless.
 */
@Entity
@Table(name = "plan_deliveries")
public class PlanDeliveryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Platform platform;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "platform_user_id", nullable = false, length = 255)
    private String platformUserId;

    @Column(name = "buyer_id_hash", length = 64)
    private String buyerIdHash;

    // Provas de entrega (Spec 49+, 2026-08-06): copiadas da transação na criação.
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "buyer_ip", length = 45)
    private String buyerIp;

    @Column(name = "buyer_device", length = 255)
    private String buyerDevice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanDeliveryStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected PlanDeliveryJpaEntity() {
    }

    public PlanDeliveryJpaEntity(UUID id, UUID storeId, UUID planId, UUID transactionId,
                                 Platform platform, String platformUserId, String buyerIdHash,
                                 PlanDeliveryStatus status, Instant createdAt, Instant deliveredAt) {
        this(id, storeId, planId, transactionId, platform, platformUserId, buyerIdHash,
                status, createdAt, deliveredAt, null, null, null);
    }

    public PlanDeliveryJpaEntity(UUID id, UUID storeId, UUID planId, UUID transactionId,
                                 Platform platform, String platformUserId, String buyerIdHash,
                                 PlanDeliveryStatus status, Instant createdAt, Instant deliveredAt,
                                 Instant termsAcceptedAt, String buyerIp, String buyerDevice) {
        this.id = id;
        this.storeId = storeId;
        this.planId = planId;
        this.transactionId = transactionId;
        this.platform = platform;
        this.platformUserId = platformUserId;
        this.buyerIdHash = buyerIdHash;
        this.termsAcceptedAt = termsAcceptedAt;
        this.buyerIp = buyerIp;
        this.buyerDevice = buyerDevice;
        this.status = status;
        this.createdAt = createdAt;
        this.deliveredAt = deliveredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Platform getPlatform() {
        return platform;
    }

    public String getPlatformUserId() {
        return platformUserId;
    }

    public String getBuyerIdHash() {
        return buyerIdHash;
    }

    public PlanDeliveryStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }

    public String getBuyerIp() {
        return buyerIp;
    }

    public String getBuyerDevice() {
        return buyerDevice;
    }
}
