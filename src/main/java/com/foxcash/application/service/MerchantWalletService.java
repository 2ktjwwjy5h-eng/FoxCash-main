package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.MerchantDashboardUseCase;
import com.foxcash.domain.port.in.MerchantWalletUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.port.out.WithdrawalRepository;
import com.foxcash.domain.service.PricingCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicacao: carteira/financeiro do lojista no portal (Spec 22) — somente
 * leitura. Saldo oficial (Spec 14), resumo do mes (volume aprovado − taxas),
 * entradas da semana (7 dias) e fluxo de caixa (entradas vs saidas).
 *
 * <p>Liquida e net podem ser negativos (saidas > entradas no periodo; taxa maior
 * que a venda) — por isso sao {@link BigDecimal} e nao {@link Money} (que nao
 * aceita negativos).
 */
@Service
@Transactional(readOnly = true)
public class MerchantWalletService implements MerchantWalletUseCase {

    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final PricingCalculator pricingCalculator;
    private final Clock clock;

    public MerchantWalletService(MerchantRepository merchantRepository,
                                 TransactionRepository transactionRepository,
                                 WithdrawalRepository withdrawalRepository,
                                 PricingCalculator pricingCalculator,
                                 Clock clock) {
        this.merchantRepository = merchantRepository;
        this.transactionRepository = transactionRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.pricingCalculator = pricingCalculator;
        this.clock = clock;
    }

    @Override
    public Balance balance(UUID merchantId) {
        Merchant merchant = requireMerchant(merchantId);
        return new Balance(merchant.walletBalance(), merchant.withdrawalsBlocked(), true);
    }

    @Override
    public MonthlySummary monthlySummary(UUID merchantId, BillingPeriod period) {
        Merchant merchant = requireMerchant(merchantId);
        Instant start = period.yearMonth().atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = period.yearMonth().atDay(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Auditoria 2026-08-05: soma e contagem via SQL (antes carregava todas
        // as transacoes aprovadas em memoria apenas para agregar).
        BigDecimal grossAmount = transactionRepository
                .sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                        merchantId, TransactionStatus.APPROVED, start, end);
        long count = transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                merchantId, TransactionStatus.APPROVED, start, end);
        Money gross = Money.of(grossAmount);
        Money fees = pricingCalculator.totalTransactionFees(merchant.pricingPlan(), (int) count);
        BigDecimal net = gross.amount().subtract(fees.amount());

        return new MonthlySummary(gross, net, count);
    }

    @Override
    public List<MerchantDashboardUseCase.TimeSeriesPoint> weeklyInflows(UUID merchantId) {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        return dailySeries(merchantId, today.minusDays(6), today);
    }

    @Override
    public CashFlow cashFlow(UUID merchantId, Instant from, Instant to) {
        List<PixTransaction> approved = transactionRepository.findApprovedByMerchantIdBetween(merchantId, from, to);
        Money inflows = approved.stream()
                .map(PixTransaction::amount)
                .reduce(Money.of("0.00"), Money::add);
        BigDecimal outflowsSum = withdrawalRepository.sumCompletedAmountByMerchantIdBetween(merchantId, from, to);
        Money outflows = Money.of(outflowsSum == null ? BigDecimal.ZERO : outflowsSum);
        BigDecimal net = inflows.amount().subtract(outflows.amount());

        return new CashFlow(inflows, outflows, net);
    }

    private Merchant requireMerchant(UUID merchantId) {
        return merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
    }

    /** Serie diaria do intervalo (dias sem venda = 0), mesma agregacao do dashboard (Spec 21). */
    private List<MerchantDashboardUseCase.TimeSeriesPoint> dailySeries(UUID merchantId, LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, Money> byDay = new LinkedHashMap<>();
        for (PixTransaction tx : transactionRepository.findApprovedByMerchantIdBetween(merchantId, start, end)) {
            LocalDate day = tx.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
            byDay.merge(day, tx.amount(), Money::add);
        }

        List<MerchantDashboardUseCase.TimeSeriesPoint> series = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            series.add(new MerchantDashboardUseCase.TimeSeriesPoint(
                    cursor, byDay.getOrDefault(cursor, Money.of("0.00"))));
            cursor = cursor.plusDays(1);
        }
        return series;
    }
}
