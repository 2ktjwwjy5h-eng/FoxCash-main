package com.foxcash.domain.model.billing;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Divida formal do lojista com a plataforma (Spec 33) — contas a receber.
 *
 * <p>Registra TODO debito do lojista com origem explicita: {@code INVOICE}
 * (fatura nao coberta no vencimento), {@code CHARGEBACK}/{@code REFUND}
 * (estorno que deixou o saldo negativo) e {@code ADJUSTMENT} (reservado). O
 * {@code amount} e sempre positivo e {@code paidAmount} rastreia o pago —
 * {@code applyPayment} evolui {@code OPEN} &rarr; {@code PARTIAL} &rarr;
 * {@code PAID}. Receivable {@code OPEN}/{@code PARTIAL} bloqueia o saque do
 * vendedor (Spec 14/33); a baixa (write-off) e acao do master (Spec 34/37).
 */
public class Receivable {

    private final UUID id;
    private final UUID merchantId;
    private final ReceivableOrigin origin;
    private final UUID referenceId;
    private final Money amount;
    private Money paidAmount;
    private ReceivableStatus status;
    private final Instant createdAt;
    private Instant paidAt;
    private Instant updatedAt;

    private Receivable(UUID id, UUID merchantId, ReceivableOrigin origin, UUID referenceId,
                       Money amount, Money paidAmount, ReceivableStatus status,
                       Instant createdAt, Instant paidAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.origin = Objects.requireNonNull(origin, "origin must not be null");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.paidAmount = Objects.requireNonNull(paidAmount, "paidAmount must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.paidAt = paidAt;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /** Cria uma divida aberta ({@code OPEN}, nada pago). */
    public static Receivable create(UUID merchantId, ReceivableOrigin origin, UUID referenceId,
                                    Money amount, Instant now) {
        return new Receivable(UUID.randomUUID(), merchantId, origin, referenceId, amount,
                Money.of("0.00"), ReceivableStatus.OPEN, now, null, now);
    }

    public static Receivable restore(UUID id, UUID merchantId, ReceivableOrigin origin, UUID referenceId,
                                     Money amount, Money paidAmount, ReceivableStatus status,
                                     Instant createdAt, Instant paidAt, Instant updatedAt) {
        return new Receivable(id, merchantId, origin, referenceId, amount, paidAmount,
                status, createdAt, paidAt, updatedAt);
    }

    /**
     * Aplica um pagamento sobre a divida (Spec 33). {@code OPEN} &rarr;
     * {@code PARTIAL} ate quitar ({@code PAID}, com {@code paidAt}). Pagamentos
     * &le; 0 sao no-op.
     */
    public void applyPayment(Money payment, Instant now) {
        if (payment.amount().signum() <= 0) {
            return;
        }
        this.paidAmount = paidAmount.add(payment);
        this.updatedAt = now;
        if (isFullyPaid()) {
            markPaid(now);
        } else {
            this.status = ReceivableStatus.PARTIAL;
        }
    }

    public void markPaid(Instant now) {
        this.status = ReceivableStatus.PAID;
        this.paidAt = now;
        this.updatedAt = now;
    }

    /** Valor ainda devido (positivo enquanto nao quitado). */
    public Money remaining() {
        return amount.subtract(paidAmount);
    }

    public boolean isFullyPaid() {
        return paidAmount.amount().compareTo(amount.amount()) >= 0;
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public ReceivableOrigin origin() {
        return origin;
    }

    public UUID referenceId() {
        return referenceId;
    }

    public Money amount() {
        return amount;
    }

    public Money paidAmount() {
        return paidAmount;
    }

    public ReceivableStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
