package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.BillingPeriod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: carteira/financeiro do lojista no portal (Spec 22) —
 * somente leitura. Saldo oficial, resumo do mês, entradas da semana e fluxo de
 * caixa (entradas vs saídas).
 */
public interface MerchantWalletUseCase {

    Balance balance(UUID merchantId);

    MonthlySummary monthlySummary(UUID merchantId, BillingPeriod period);

    /** Entradas da semana: 7 pontos {label, value} (dia × volume aprovado, UTC). */
    List<MerchantDashboardUseCase.TimeSeriesPoint> weeklyInflows(UUID merchantId);

    CashFlow cashFlow(UUID merchantId, Instant from, Instant to);

    /**
     * Saldo disponível do lojista (fonte oficial, Spec 14). {@code saldoCalculadoPelaApi}
     * é sempre {@code true} — o valor vem do fluxo oficial, nunca de estimativa local.
     */
    record Balance(Money walletBalance, boolean withdrawalsBlocked, boolean saldoCalculadoPelaApi) {
    }

    /** Resumo do mês: bruta, líquida (bruta − taxas de transação) e contagem. */
    record MonthlySummary(Money grossRevenue, BigDecimal netRevenue, long transactionCount) {
    }

    /** Fluxo de caixa no período: entradas (aprovadas) vs saídas (saques COMPLETED). */
    record CashFlow(Money inflows, Money outflows, BigDecimal net) {
    }
}
