package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de issue de reconciliação (Spec 30). A unique parcial
 * {@code uq_reconciliation_open} (merchant_id onde status='OPEN') é garantida
 * pela migration V19 — o {@code open} do adapter é idempotente.
 */
@Entity
@Table(name = "reconciliation_issues",
        indexes = {
                @Index(name = "ix_reconciliation_issues_merchant", columnList = "merchant_id, status")
        })
public class ReconciliationIssueJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "expected_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal expectedBalance;

    @Column(name = "actual_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal actualBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationIssueStatus status;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReconciliationIssueJpaEntity() {
    }

    public ReconciliationIssueJpaEntity(UUID id, UUID merchantId, BigDecimal expectedBalance,
                                        BigDecimal actualBalance, ReconciliationIssueStatus status,
                                        Instant detectedAt, Instant resolvedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.expectedBalance = expectedBalance;
        this.actualBalance = actualBalance;
        this.status = status;
        this.detectedAt = detectedAt;
        this.resolvedAt = resolvedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public BigDecimal getExpectedBalance() {
        return expectedBalance;
    }

    public BigDecimal getActualBalance() {
        return actualBalance;
    }

    public ReconciliationIssueStatus getStatus() {
        return status;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
