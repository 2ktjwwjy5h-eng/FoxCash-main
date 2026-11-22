package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.billing.InvoiceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Entidade JPA de fatura (desacoplada do modelo de dominio).
 */
@Entity
@Table(name = "invoices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_invoices_merchant_period", columnNames = {"merchant_id", "billing_period"})
        })
public class InvoiceJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "billing_period", nullable = false, length = 7)
    private String billingPeriod;

    @Column(name = "transaction_count", nullable = false)
    private int transactionCount;

    @Column(name = "transaction_fees_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal transactionFeesTotal;

    @Column(name = "monthly_fee", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyFee;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "collected_at")
    private Instant collectedAt;

    protected InvoiceJpaEntity() {
    }

    public InvoiceJpaEntity(UUID id, UUID merchantId, String billingPeriod, int transactionCount,
                            BigDecimal transactionFeesTotal, BigDecimal monthlyFee, BigDecimal totalAmount,
                            InvoiceStatus status, LocalDate dueDate, Instant createdAt,
                            BigDecimal paidAmount, Instant collectedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.billingPeriod = billingPeriod;
        this.transactionCount = transactionCount;
        this.transactionFeesTotal = transactionFeesTotal;
        this.monthlyFee = monthlyFee;
        this.totalAmount = totalAmount;
        this.status = status;
        this.dueDate = dueDate;
        this.createdAt = createdAt;
        this.paidAmount = paidAmount;
        this.collectedAt = collectedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getBillingPeriod() {
        return billingPeriod;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public BigDecimal getTransactionFeesTotal() {
        return transactionFeesTotal;
    }

    public BigDecimal getMonthlyFee() {
        return monthlyFee;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }
}
