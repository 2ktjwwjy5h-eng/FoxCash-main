package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de chargeback (Spec 19) — desacoplada do dominio.
 */
@Entity
@Table(name = "chargebacks")
public class ChargebackJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "buyer_id_hash", length = 64)
    private String buyerIdHash;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChargebackJpaEntity() {
    }

    public ChargebackJpaEntity(UUID id, UUID merchantId, UUID transactionId, String buyerIdHash,
                               BigDecimal amount, String reason, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.transactionId = transactionId;
        this.buyerIdHash = buyerIdHash;
        this.amount = amount;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getBuyerIdHash() {
        return buyerIdHash;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
