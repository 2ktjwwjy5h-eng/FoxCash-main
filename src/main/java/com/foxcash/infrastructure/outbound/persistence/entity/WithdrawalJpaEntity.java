package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.withdrawal.WithdrawalStatus;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.infrastructure.security.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de saida (saque) — desacoplada do modelo de dominio (Spec 14,
 * evoluida pela Spec 32: destino/tipo/snapshot criptografado; Spec 36: estado
 * PROCESSING/COMPLETED/FAILED + external_reference/payout_provider/falha).
 */
@Entity
@Table(name = "withdrawals")
public class WithdrawalJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "fee_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal feeAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WithdrawalStatus status;

    @Column(name = "destination_id")
    private UUID destinationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WithdrawalType type;

    @Column(length = 255)
    private String reason;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "destination_key_snapshot", length = 255)
    private String destinationKeySnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Column(name = "payout_provider", length = 30)
    private String payoutProvider;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "failed_at")
    private Instant failedAt;

    protected WithdrawalJpaEntity() {
    }

    public WithdrawalJpaEntity(UUID id, UUID merchantId, BigDecimal amount, BigDecimal feeAmount,
                               WithdrawalStatus status,
                               UUID destinationId, WithdrawalType type, String reason,
                               String destinationKeySnapshot, Instant createdAt,
                               Instant completedAt, Instant rejectedAt) {
        this(id, merchantId, amount, feeAmount, status, destinationId, type, reason,
                destinationKeySnapshot, createdAt, completedAt, rejectedAt,
                null, null, null, null);
    }

    public WithdrawalJpaEntity(UUID id, UUID merchantId, BigDecimal amount, BigDecimal feeAmount,
                               WithdrawalStatus status,
                               UUID destinationId, WithdrawalType type, String reason,
                               String destinationKeySnapshot, Instant createdAt,
                               Instant completedAt, Instant rejectedAt,
                               String externalReference, String payoutProvider,
                               String failureReason, Instant failedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.feeAmount = feeAmount;
        this.status = status;
        this.destinationId = destinationId;
        this.type = type;
        this.reason = reason;
        this.destinationKeySnapshot = destinationKeySnapshot;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.rejectedAt = rejectedAt;
        this.externalReference = externalReference;
        this.payoutProvider = payoutProvider;
        this.failureReason = failureReason;
        this.failedAt = failedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public WithdrawalStatus getStatus() {
        return status;
    }

    public UUID getDestinationId() {
        return destinationId;
    }

    public WithdrawalType getType() {
        return type;
    }

    public String getReason() {
        return reason;
    }

    public String getDestinationKeySnapshot() {
        return destinationKeySnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getPayoutProvider() {
        return payoutProvider;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getFailedAt() {
        return failedAt;
    }
}
