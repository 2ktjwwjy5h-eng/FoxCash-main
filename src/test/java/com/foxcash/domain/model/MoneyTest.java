package com.foxcash.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void normalizesScaleToTwoDecimals() {
        assertEquals(Money.of("10.50"), Money.of("10.5"));
    }

    @Test
    void addsAmounts() {
        assertEquals(Money.of("10.00"), Money.of("4.50").add(Money.of("5.50")));
    }

    @Test
    void allowsNegativeAmounts() {
        // Saldo do lojista pode ficar negativo (estorno após saque).
        assertEquals(Money.of("-1.00"), Money.of("-1.00"));
        assertEquals(Money.of("-30.00"), Money.of("20.00").subtract(Money.of("50.00")));
    }

    @Test
    void acceptsValuesRepresentableInCents() {
        // Zeros a direita alem de 2 casas nao mudam o valor (10.500 == 10.50).
        assertEquals(Money.of("10.50"), Money.of("10.500"));
        assertEquals(Money.of("5.00"), Money.of("5"));
        assertEquals(Money.of("5.00"), Money.of("5.000"));
    }

    @Test
    void rejectsMoreThanTwoDecimalPlaces() {
        // Auditoria 2026-08-05: sem arredondamento silencioso.
        assertThrows(IllegalArgumentException.class, () -> Money.of("10.009"));
        assertThrows(IllegalArgumentException.class, () -> Money.of("-0.001"));
        assertThrows(IllegalArgumentException.class, () -> Money.of("0.999"));
    }

    @Test
    void normalizeScalesFinancialValuesToTwo() {
        // Spec 30 (L2): valores financeiros no JSON com escala 2 — 150.00, não 150.0.
        assertEquals(2, Money.normalize(new java.math.BigDecimal("150.0")).scale());
        assertEquals(new java.math.BigDecimal("150.00"), Money.normalize(new java.math.BigDecimal("150.0")));
        assertEquals(new java.math.BigDecimal("0.00"), Money.normalize(new java.math.BigDecimal("0")));
    }
}
