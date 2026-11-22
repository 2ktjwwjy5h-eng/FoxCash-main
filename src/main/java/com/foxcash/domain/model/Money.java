package com.foxcash.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetário (BRL) imutável, com escala normalizada para 2 casas decimais.
 *
 * <p>Pode ser negativo: o saldo do lojista fica devedor quando um estorno
 * (refund/chargeback) chega depois de o lojista já ter sacado o valor
 * (decisão 2026-08-04). Cada uso valida seu próprio domínio — ex.: uma
 * transação exige valor positivo em {@code PixTransaction.create}.
 *
 * <p>Auditoria 2026-08-05: valores com MAIS de 2 casas decimais sao REJEITADOS
 * (400 {@code invalid_argument}) em vez de arredondados silenciosamente —
 * 10.500 e valido (equivale a 10.50), 10.009 nao.
 */
public record Money(BigDecimal amount) {

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Valor monetario com mais de 2 casas decimais: " + amount);
        }
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value));
    }

    /**
     * Normaliza a escala de um {@link BigDecimal} monetário para 2 casas
     * (Spec 30 — contrato estável no JSON, ex.: {@code 150.00} e não
     * {@code 150.0}). Usado em valores vindos de {@code SUM} do PostgreSQL.
     */
    public static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static Money of(BigDecimal value) {
        return new Money(value);
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount));
    }

    /**
     * Subtrai outro valor. Pode resultar em valor negativo — o chamador
     * decide a regra (ex.: {@code Merchant.withdraw} valida o saldo antes;
     * {@code Merchant.debit} permite saldo devedor no estorno).
     */
    public Money subtract(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money multiply(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)));
    }
}
