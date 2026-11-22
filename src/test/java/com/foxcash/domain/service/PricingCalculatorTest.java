package com.foxcash.domain.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.pricing.PricingPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testes do modelo de precificacao fixa FoxCash
 * (R$ 0,50 por transacao + R$ 69,95 mensais).
 *
 * <p>Spec 98 (D4.1): override por lojista — FIXED (fixa por transacao),
 * PERCENT (% acima do threshold, fixa ate la), fallback global sem override.
 */
class PricingCalculatorTest {

    private final PricingCalculator calculator = new PricingCalculator();

    @Test
    void totalTransactionFees_multipliesFixedFeeByCount() {
        PricingPlan plan = PricingPlan.fixed();

        Money total = calculator.totalTransactionFees(plan, 10);

        assertEquals(Money.of("5.00"), total);
    }

    @Test
    void monthlyTotal_addsMonthlyFee() {
        PricingPlan plan = PricingPlan.fixed();

        Money total = calculator.monthlyTotal(plan, 10);

        // 10 * 0,50 + 69,95 = 74,95
        assertEquals(Money.of("74.95"), total);
    }

    @Test
    void monthlyTotal_withZeroTransactions_stillChargesMonthlyFee() {
        PricingPlan plan = PricingPlan.fixed();

        Money total = calculator.monthlyTotal(plan, 0);

        assertEquals(Money.of("69.95"), total);
    }

    // ---- Spec 98 (D4.1): override da config fina por lojista ----

    @Test
    void transactionFee_withoutOverride_fallsBackToPlan() {
        PricingPlan plan = PricingPlan.fixed();

        assertEquals(Money.of("0.50"),
                calculator.transactionFee(plan, MerchantConfig.defaults(), Money.of("100.00")));
        assertEquals(Money.of("0.50"), calculator.transactionFee(plan, null, Money.of("100.00")));
    }

    @Test
    void transactionFee_fixedOverrideSubstitutesPlanFee() {
        PricingPlan plan = PricingPlan.fixed();
        MerchantConfig config = new MerchantConfig(MerchantConfig.PixFeeType.FIXED,
                Money.of("1.25"), null, null, null, null, null, null, null,
                null, null, false, false);

        assertEquals(Money.of("1.25"), calculator.transactionFee(plan, config, Money.of("10.00")));
    }

    @Test
    void transactionFee_percentAboveThresholdChargesPercent() {
        PricingPlan plan = PricingPlan.fixed();
        MerchantConfig config = new MerchantConfig(MerchantConfig.PixFeeType.PERCENT,
                Money.of("1.00"), new BigDecimal("2"), Money.of("100.00"),
                null, null, null, null, null, null, null, false, false);

        // 150 > 100 → 150 * 2% = 3,00
        assertEquals(Money.of("3.00"), calculator.transactionFee(plan, config, Money.of("150.00")));
    }

    @Test
    void transactionFee_percentAtOrBelowThresholdChargesFixed() {
        PricingPlan plan = PricingPlan.fixed();
        MerchantConfig config = new MerchantConfig(MerchantConfig.PixFeeType.PERCENT,
                Money.of("1.00"), new BigDecimal("2"), Money.of("100.00"),
                null, null, null, null, null, null, null, false, false);

        // 100 <= threshold → fixa 1,00 (nao 100 * 2%)
        assertEquals(Money.of("1.00"), calculator.transactionFee(plan, config, Money.of("100.00")));
        assertEquals(Money.of("1.00"), calculator.transactionFee(plan, config, Money.of("50.00")));
    }

    @Test
    void totalTransactionFees_fixedOverrideMultipliesByCount() {
        PricingPlan plan = PricingPlan.fixed();
        MerchantConfig config = new MerchantConfig(MerchantConfig.PixFeeType.FIXED,
                Money.of("1.25"), null, null, null, null, null, null, null,
                null, null, false, false);

        // 10 * 1,25 = 12,50
        assertEquals(Money.of("12.50"), calculator.totalTransactionFees(plan, config, 10));
    }

    @Test
    void totalTransactionFees_percentOverrideFallsBackToPlanForCount() {
        // A fatura mensal agrega por CONTAGEM (sem valores por transacao) — o
        // PERCENT depende do valor de cada venda e usa a taxa do plano.
        PricingPlan plan = PricingPlan.fixed();
        MerchantConfig config = new MerchantConfig(MerchantConfig.PixFeeType.PERCENT,
                Money.of("1.00"), new BigDecimal("2"), Money.of("100.00"),
                null, null, null, null, null, null, null, false, false);

        assertEquals(Money.of("5.00"), calculator.totalTransactionFees(plan, config, 10));
    }
}
