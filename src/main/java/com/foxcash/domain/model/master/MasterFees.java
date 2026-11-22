package com.foxcash.domain.model.master;

import com.foxcash.domain.model.Money;

/**
 * Taxas de um lojista num período (Spec 15) — derivadas das faturas.
 * Sem fatura no período, todos os valores sao zero.
 */
public record MasterFees(
        int transactionCount,
        Money transactionFees,
        Money monthlyFee,
        Money totalFees) {
}
