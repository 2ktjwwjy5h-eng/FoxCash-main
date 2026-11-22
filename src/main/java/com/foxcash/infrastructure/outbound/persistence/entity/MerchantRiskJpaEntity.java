package com.foxcash.infrastructure.outbound.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de snapshot de risco (Spec 16) — desacoplada do dominio.
 */
@Entity
@Table(name = "merchant_risk")
public class MerchantRiskJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "approved_volume", nullable = false, precision = 15, scale = 2)
    private BigDecimal approvedVolume;

    @Column(name = "total_transactions", nullable = false)
    private long totalTransactions;

    @Column(name = "approved_transactions", nullable = false)
    private long approvedTransactions;

    @Column(name = "refunded_transactions", nullable = false)
    private long refundedTransactions;

    @Column(name = "refund_rate_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal refundRatePercent;

    @Column(name = "chargeback_rate_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal chargebackRatePercent;

    @Column(name = "complaints_count", nullable = false)
    private int complaintsCount;

    @Column(name = "refund_alert", nullable = false)
    private boolean refundAlert;

    @Column(name = "chargeback_alert", nullable = false)
    private boolean chargebackAlert;

    @Column(name = "complaints_alert", nullable = false)
    private boolean complaintsAlert;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    protected MerchantRiskJpaEntity() {
    }

    public MerchantRiskJpaEntity(UUID id, UUID merchantId, Instant computedAt, BigDecimal approvedVolume,
                                 long totalTransactions, long approvedTransactions, long refundedTransactions,
                                 BigDecimal refundRatePercent, BigDecimal chargebackRatePercent,
                                 int complaintsCount, boolean refundAlert, boolean chargebackAlert,
                                 boolean complaintsAlert, int riskScore) {
        this.id = id;
        this.merchantId = merchantId;
        this.computedAt = computedAt;
        this.approvedVolume = approvedVolume;
        this.totalTransactions = totalTransactions;
        this.approvedTransactions = approvedTransactions;
        this.refundedTransactions = refundedTransactions;
        this.refundRatePercent = refundRatePercent;
        this.chargebackRatePercent = chargebackRatePercent;
        this.complaintsCount = complaintsCount;
        this.refundAlert = refundAlert;
        this.chargebackAlert = chargebackAlert;
        this.complaintsAlert = complaintsAlert;
        this.riskScore = riskScore;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public BigDecimal getApprovedVolume() {
        return approvedVolume;
    }

    public long getTotalTransactions() {
        return totalTransactions;
    }

    public long getApprovedTransactions() {
        return approvedTransactions;
    }

    public long getRefundedTransactions() {
        return refundedTransactions;
    }

    public BigDecimal getRefundRatePercent() {
        return refundRatePercent;
    }

    public BigDecimal getChargebackRatePercent() {
        return chargebackRatePercent;
    }

    public int getComplaintsCount() {
        return complaintsCount;
    }

    public boolean isRefundAlert() {
        return refundAlert;
    }

    public boolean isChargebackAlert() {
        return chargebackAlert;
    }

    public boolean isComplaintsAlert() {
        return complaintsAlert;
    }

    public int getRiskScore() {
        return riskScore;
    }
}
