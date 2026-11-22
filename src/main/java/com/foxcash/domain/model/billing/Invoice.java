package com.foxcash.domain.model.billing;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fatura mensal do lojista, calculada a partir do plano de precificacao
 * (R$ 0,50 por transacao aprovada + R$ 69,95 de mensalidade).
 */
public class Invoice {

    private final UUID id;
    private final UUID merchantId;
    private final BillingPeriod period;
    private final int transactionCount;
    private final Money transactionFeesTotal;
    private final Money monthlyFee;
    private final Money totalAmount;
    private final LocalDate dueDate;
    private InvoiceStatus status;
    private final Instant createdAt;
    private Money paidAmount;
    private Instant collectedAt;

    private Invoice(UUID id, UUID merchantId, BillingPeriod period, int transactionCount,
                    Money transactionFeesTotal, Money monthlyFee, Money totalAmount,
                    LocalDate dueDate, InvoiceStatus status, Instant createdAt,
                    Money paidAmount, Instant collectedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.period = period;
        this.transactionCount = transactionCount;
        this.transactionFeesTotal = transactionFeesTotal;
        this.monthlyFee = monthlyFee;
        this.totalAmount = totalAmount;
        this.dueDate = dueDate;
        this.status = status;
        this.createdAt = createdAt;
        this.paidAmount = paidAmount;
        this.collectedAt = collectedAt;
    }

    public static Invoice draft(UUID merchantId, BillingPeriod period, int transactionCount,
                                Money transactionFeesTotal, Money monthlyFee, LocalDate dueDate) {
        Money total = transactionFeesTotal.add(monthlyFee);
        return new Invoice(UUID.randomUUID(), merchantId, period, transactionCount,
                transactionFeesTotal, monthlyFee, total, dueDate, InvoiceStatus.PENDING,
                Instant.now(), Money.of("0.00"), null);
    }

    public static Invoice restore(UUID id, UUID merchantId, BillingPeriod period, int transactionCount,
                                  Money transactionFeesTotal, Money monthlyFee, Money totalAmount,
                                  LocalDate dueDate, InvoiceStatus status, Instant createdAt,
                                  Money paidAmount, Instant collectedAt) {
        return new Invoice(id, merchantId, period, transactionCount,
                transactionFeesTotal, monthlyFee, totalAmount, dueDate, status, createdAt,
                paidAmount, collectedAt);
    }

    public void markPaid() {
        requireStatus(InvoiceStatus.PENDING, InvoiceStatus.OVERDUE);
        this.status = InvoiceStatus.PAID;
    }

    public void markOverdue() {
        requireStatus(InvoiceStatus.PENDING, InvoiceStatus.OVERDUE);
        this.status = InvoiceStatus.OVERDUE;
    }

    /**
     * Aplica um pagamento da cobranca (Spec 33): soma em {@code paidAmount} e
     * registra {@code collectedAt}. Nao muda o status — o chamador decide entre
     * {@code markPaid} (quitada) e {@code markOverdue} (parcial). Uma fatura
     * {@code OVERDUE} e re-cobrada pelo job quando o lojista volta a ter saldo.
     */
    public void applyPayment(Money amount, Instant now) {
        requireStatus(InvoiceStatus.PENDING, InvoiceStatus.OVERDUE);
        if (amount.amount().signum() < 0) {
            throw new IllegalArgumentException("Payment amount must not be negative");
        }
        this.paidAmount = paidAmount.add(amount);
        this.collectedAt = now;
    }

    /** Valor ainda devido (positivo enquanto nao quitada). */
    public Money outstanding() {
        return totalAmount.subtract(paidAmount);
    }

    public boolean isFullyPaid() {
        return outstanding().amount().signum() <= 0;
    }

    public void cancel() {
        this.status = InvoiceStatus.CANCELLED;
    }

    private void requireStatus(InvoiceStatus... expected) {
        for (InvoiceStatus candidate : expected) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException(
                "Invoice " + id + " is " + status + ", expected " + java.util.Arrays.toString(expected));
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public BillingPeriod period() {
        return period;
    }

    public int transactionCount() {
        return transactionCount;
    }

    public Money transactionFeesTotal() {
        return transactionFeesTotal;
    }

    public Money monthlyFee() {
        return monthlyFee;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    public InvoiceStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Total pago ate o momento pela cobranca (Spec 33) — 0 enquanto nao cobrada. */
    public Money paidAmount() {
        return paidAmount;
    }

    /** Data da ultima cobranca processada (Spec 33) — null enquanto nao cobrada. */
    public Instant collectedAt() {
        return collectedAt;
    }
}
