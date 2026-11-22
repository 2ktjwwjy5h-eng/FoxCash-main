package com.foxcash.domain.model.pricing;

import com.foxcash.domain.model.Money;

/**
 * Plano de precificacao do lojista.
 * Modelo fixo FoxCash: R$ 0,50 por transacao + R$ 69,95 mensais.
 */
public record PricingPlan(Money transactionFee, Money monthlyFee) {

    public PricingPlan {
        if (transactionFee == null) {
            throw new IllegalArgumentException("transactionFee must not be null");
        }
        if (monthlyFee == null) {
            throw new IllegalArgumentException("monthlyFee must not be null");
        }
    }

    public static PricingPlan fixed() {
        return new PricingPlan(Money.of("0.50"), Money.of("69.95"));
    }
}
