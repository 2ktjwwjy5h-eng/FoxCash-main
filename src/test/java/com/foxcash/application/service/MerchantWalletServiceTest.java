package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.port.out.WithdrawalRepository;
import com.foxcash.domain.service.PricingCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes da carteira/financeiro do lojista (Spec 22): saldo oficial, resumo do
 * mes (bruta − taxas), entradas da semana (7 pontos) e fluxo de caixa.
 */
class MerchantWalletServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final WithdrawalRepository withdrawalRepository = mock(WithdrawalRepository.class);
    private final PricingCalculator pricingCalculator = new PricingCalculator();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private MerchantWalletService service;

    @BeforeEach
    void setUp() {
        service = new MerchantWalletService(
                merchantRepository, transactionRepository, withdrawalRepository, pricingCalculator, clock);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(
                Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                        null, MerchantStatus.ACTIVE, PLAN, Money.of("100.00"), false, NOW)));
    }

    private PixTransaction tx(Instant createdAt, String amount) {
        return PixTransaction.restore(UUID.randomUUID(), MERCHANT_ID, Money.of(amount),
                "Cobranca", null, "REF", "REF", null, null, TransactionStatus.APPROVED,
                createdAt, createdAt, createdAt, "buyerhash");
    }

    @Test
    void balanceReturnsWalletAndWithdrawalsBlockedFlag() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(
                Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                        null, MerchantStatus.ACTIVE, PLAN, Money.of("250.50"), true, NOW)));

        var balance = service.balance(MERCHANT_ID);

        assertThat(balance.walletBalance().amount()).isEqualByComparingTo("250.50");
        assertThat(balance.withdrawalsBlocked()).isTrue();
        assertThat(balance.saldoCalculadoPelaApi()).isTrue();
    }

    @Test
    void monthlySummaryComputesGrossNetAndCount() {
        Instant start = YearMonth.of(2026, 8).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = YearMonth.of(2026, 8).atDay(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // Auditoria 2026-08-05: agregacao via SQL (soma + contagem).
        when(transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, start, end)).thenReturn(new BigDecimal("200.00"));
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, start, end)).thenReturn(2L);

        var summary = service.monthlySummary(MERCHANT_ID, BillingPeriod.of(YearMonth.of(2026, 8)));

        assertThat(summary.grossRevenue().amount()).isEqualByComparingTo("200.00");
        assertThat(summary.netRevenue()).isEqualByComparingTo("199.00"); // 200 - 2×0,50
        assertThat(summary.transactionCount()).isEqualTo(2);
    }

    @Test
    void monthlySummaryNetCanBeNegativeWhenFeeExceedsSale() {
        Instant start = YearMonth.of(2026, 8).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = YearMonth.of(2026, 8).atDay(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        when(transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, start, end)).thenReturn(new BigDecimal("0.30"));
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, start, end)).thenReturn(1L);

        var summary = service.monthlySummary(MERCHANT_ID, BillingPeriod.of(YearMonth.of(2026, 8)));

        assertThat(summary.grossRevenue().amount()).isEqualByComparingTo("0.30");
        assertThat(summary.netRevenue()).isEqualByComparingTo("-0.20"); // 0,30 - 0,50
        assertThat(summary.transactionCount()).isEqualTo(1);
    }

    @Test
    void weeklyInflowsReturnsSevenDaysEndingTodayZeroFilled() {
        when(transactionRepository.findApprovedByMerchantIdBetween(eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of(tx(Instant.parse("2026-08-04T10:00:00Z"), "120.00")));

        var series = service.weeklyInflows(MERCHANT_ID);

        assertThat(series).hasSize(7);
        assertThat(series.get(0).label()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(series.get(6).label()).isEqualTo(LocalDate.of(2026, 8, 4));
        assertThat(series.get(6).value().amount()).isEqualByComparingTo("120.00");
        for (int i = 0; i < 6; i++) {
            assertThat(series.get(i).value().amount()).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void cashFlowComputesInflowsOutflowsAndNet() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-05T00:00:00Z");
        when(transactionRepository.findApprovedByMerchantIdBetween(MERCHANT_ID, from, to))
                .thenReturn(List.of(
                        tx(Instant.parse("2026-08-01T10:00:00Z"), "150.00"),
                        tx(Instant.parse("2026-08-02T10:00:00Z"), "50.00")));
        when(withdrawalRepository.sumCompletedAmountByMerchantIdBetween(MERCHANT_ID, from, to))
                .thenReturn(new BigDecimal("80.00"));

        var flow = service.cashFlow(MERCHANT_ID, from, to);

        assertThat(flow.inflows().amount()).isEqualByComparingTo("200.00");
        assertThat(flow.outflows().amount()).isEqualByComparingTo("80.00");
        assertThat(flow.net()).isEqualByComparingTo("120.00");
    }

    @Test
    void cashFlowNetNegativeWhenOutflowsExceedInflows() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-05T00:00:00Z");
        when(transactionRepository.findApprovedByMerchantIdBetween(MERCHANT_ID, from, to))
                .thenReturn(List.of(tx(Instant.parse("2026-08-01T10:00:00Z"), "50.00")));
        when(withdrawalRepository.sumCompletedAmountByMerchantIdBetween(MERCHANT_ID, from, to))
                .thenReturn(new BigDecimal("80.00"));

        var flow = service.cashFlow(MERCHANT_ID, from, to);

        assertThat(flow.inflows().amount()).isEqualByComparingTo("50.00");
        assertThat(flow.outflows().amount()).isEqualByComparingTo("80.00");
        assertThat(flow.net()).isEqualByComparingTo("-30.00");
    }
}
