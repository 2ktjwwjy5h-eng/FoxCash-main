package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de receivable (contas a receber, Spec 33) — desacoplada do
 * modelo de dominio. A unique {@code uq_receivables_origin} e o backstop de
 * idempotencia (uma divida por origem).
 */
@Entity
@Table(name = "receivables",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_receivables_origin",
                        columnNames = {"merchant_id", "origin", "reference_id"})
        })
public class ReceivableJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReceivableOrigin origin;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReceivableStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReceivableJpaEntity() {
    }

    public ReceivableJpaEntity(UUID id, UUID merchantId, ReceivableOrigin origin, UUID referenceId,
                               BigDecimal amount, BigDecimal paidAmount, ReceivableStatus status,
                               Instant createdAt, Instant paidAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.origin = origin;
        this.referenceId = referenceId;
        this.amount = amount;
        this.paidAmount = paidAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public ReceivableOrigin getOrigin() {
        return origin;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public ReceivableStatus getStatus() {
        return status;
    }

    public void setStatus(ReceivableStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
