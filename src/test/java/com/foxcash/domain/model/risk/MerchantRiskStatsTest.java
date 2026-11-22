package com.foxcash.domain.model.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link MerchantRiskStats} (Spec 128): fórmula do MED 30d
 * {@code (refunded + chargebacks) ÷ paidCount × 100} com
 * {@code paidCount = approved + refunded} (uma estornada JÁ foi paga — achado
 * do smoke 2026-08-16) e fail-safe de zero vendas pagas.
 */
class MerchantRiskStatsTest {

    @Test
    void medPercentIsOccurrencesOverPaid() {
        // 100 aprovadas, 0 estornadas, 15 chargebacks = 15%.
        assertThat(new MerchantRiskStats(100, 0, 15).medPercent()).isEqualByComparingTo("15.00");
    }

    @Test
    void refundedCountsInNumeratorAndDenominator() {
        // 90 aprovadas + 10 estornadas = 100 pagas; 10 ocorrências = 10%.
        assertThat(new MerchantRiskStats(90, 10, 0).medPercent()).isEqualByComparingTo("10.00");
    }

    @Test
    void allRefundedIsHighMedNotZero() {
        // Achado do smoke: 10 estornadas e 0 aprovadas — as vendas JÁ foram
        // pagas (paidCount 10) → MED 100% (o fail-safe não mascara o risco).
        assertThat(new MerchantRiskStats(0, 10, 0).medPercent()).isEqualByComparingTo("100.00");
        assertThat(new MerchantRiskStats(0, 10, 0).paidCount()).isEqualTo(10);
    }

    @Test
    void zeroPaidIsZero() {
        // Fail-safe: sem vendas pagas na janela o engine nunca age (MED = 0).
        assertThat(new MerchantRiskStats(0, 0, 5).medPercent()).isEqualByComparingTo("0.00");
        assertThat(new MerchantRiskStats(0, 0, 5).paidCount()).isZero();
    }

    @Test
    void roundingIsHalfEvenTwoDecimals() {
        assertThat(new MerchantRiskStats(2, 1, 0).medPercent()).isEqualByComparingTo("33.33");
    }
}
