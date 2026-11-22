package com.foxcash.domain.model.billing;

import com.foxcash.domain.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes da {@link Invoice} (Spec 6/33): draft com total calculado, transicoes
 * markPaid/markOverdue (gate PENDING|OVERDUE) e applyPayment (acumula paidAmount,
 * registra collectedAt, rejeita negativo, aceita zero — mutation boundary).
 */
class InvoiceTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final BillingPeriod PERIOD = BillingPeriod.of(YearMonth.of(2026, 8));

    private static Invoice draft() {
        return Invoice.draft(MERCHANT, PERIOD, 10, Money.of("5.00"), Money.of("69.95"),
                LocalDate.of(2026, 9, 5));
    }

    @Test
    void draftComputesTotalAmountAndPending() {
        Invoice invoice = draft();

        assertThat(invoice.totalAmount()).isEqualTo(Money.of("74.95"));
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(invoice.paidAmount()).isEqualTo(Money.of("0.00"));
        assertThat(invoice.collectedAt()).isNull();
    }

    @Test
    void markPaidTransitionsFromPending() {
        Invoice invoice = draft();

        invoice.markPaid();

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void markPaidTwiceThrows() {
        Invoice invoice = draft();
        invoice.markPaid();

        assertThatThrownBy(invoice::markPaid).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markOverdueTransitionsFromPending() {
        Invoice invoice = draft();

        invoice.markOverdue();

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    void markOverdueTwiceThrows() {
        // OVERDUE->OVERDUE é permitido (re-cobrança); o throw é de status inválido.
        Invoice cancelled = Invoice.restore(UUID.randomUUID(), MERCHANT, PERIOD, 1,
                Money.of("0.50"), Money.of("69.95"), Money.of("70.45"),
                LocalDate.of(2026, 9, 5), InvoiceStatus.CANCELLED, NOW, Money.of("0.00"), null);

        assertThatThrownBy(cancelled::markOverdue).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyPaymentAccumulatesPaidAmountAndRecordsTime() {
        Invoice invoice = draft();

        invoice.applyPayment(Money.of("50.00"), NOW);

        assertThat(invoice.paidAmount()).isEqualTo(Money.of("50.00"));
        assertThat(invoice.collectedAt()).isEqualTo(NOW);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING); // status nao muda

        invoice.applyPayment(Money.of("24.95"), NOW.plusSeconds(60));

        assertThat(invoice.paidAmount()).isEqualTo(Money.of("74.95"));
        assertThat(invoice.outstanding()).isEqualTo(Money.of("0.00"));
        assertThat(invoice.isFullyPaid()).isTrue();
    }

    @Test
    void applyPaymentAcceptsZero() {
        // Boundary (mutation: signum() < 0 <-> <= 0): zero e aceito.
        Invoice invoice = draft();

        invoice.applyPayment(Money.of("0.00"), NOW);

        assertThat(invoice.paidAmount()).isEqualTo(Money.of("0.00"));
        assertThat(invoice.collectedAt()).isEqualTo(NOW);
    }

    @Test
    void applyPaymentRejectsNegative() {
        Invoice invoice = draft();

        assertThatThrownBy(() -> invoice.applyPayment(Money.of("-1"), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void applyPaymentOnCancelledThrows() {
        Invoice invoice = Invoice.restore(UUID.randomUUID(), MERCHANT, PERIOD, 1,
                Money.of("0.50"), Money.of("69.95"), Money.of("70.45"),
                LocalDate.of(2026, 9, 5), InvoiceStatus.CANCELLED, NOW, Money.of("0.00"), null);

        assertThatThrownBy(() -> invoice.applyPayment(Money.of("10"), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markPaidOnCancelledThrows() {
        Invoice invoice = Invoice.restore(UUID.randomUUID(), MERCHANT, PERIOD, 1,
                Money.of("0.50"), Money.of("69.95"), Money.of("70.45"),
                LocalDate.of(2026, 9, 5), InvoiceStatus.CANCELLED, NOW, Money.of("0.00"), null);

        assertThatThrownBy(invoice::markPaid).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outstandingIsTotalMinusPaid() {
        Invoice invoice = draft();

        assertThat(invoice.outstanding()).isEqualTo(Money.of("74.95"));
        assertThat(invoice.isFullyPaid()).isFalse();

        invoice.applyPayment(Money.of("74.95"), NOW);

        assertThat(invoice.isFullyPaid()).isTrue();
        assertThat(invoice.outstanding()).isEqualTo(Money.of("0.00"));
    }
}
