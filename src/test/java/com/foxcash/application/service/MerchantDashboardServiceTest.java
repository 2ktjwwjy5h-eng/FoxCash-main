package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.RiskRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * Testes do {@link MerchantDashboardService} (Spec 21): summary com conversao
 * PIX e jornada do player, serie de faturamento contínua, ultimas vendas e
 * pontos de atenção.
 */
class MerchantDashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final RiskRepository riskRepository = mock(RiskRepository.class);
    private final PortalProperties portalProperties = new PortalProperties();

    private MerchantDashboardService service;

    @BeforeEach
    void setUp() {
        service = new MerchantDashboardService(merchantRepository, transactionRepository, riskRepository, portalProperties);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(
                Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                        null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, NOW)));
    }

    private PixTransaction tx(Instant createdAt, String amount) {
        return PixTransaction.restore(UUID.randomUUID(), MERCHANT_ID, Money.of(amount),
                "Cobranca", null, "REF", "REF", null, null, TransactionStatus.APPROVED,
                createdAt, createdAt, createdAt, "buyerhash");
    }

    @Test
    void summaryComputesConversionAndJourneyProgress() {
        Instant from = NOW.minusSeconds(60);
        Instant to = NOW;
        // Auditoria 2026-08-05 (M3): metricas da janela from/to (nao all-time).
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, from, to)).thenReturn(80L);
        when(transactionRepository.countByMerchantIdAndCreatedAtBetween(MERCHANT_ID, from, to)).thenReturn(100L);
        when(transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, from, to)).thenReturn(new BigDecimal("500.00"));
        portalProperties.setJourneyGoalAmount(new BigDecimal("1000.00"));

        var summary = service.summary(MERCHANT_ID, null, from, to);

        assertThat(summary.sales()).isEqualTo(80);
        assertThat(summary.pixConversionRate()).isEqualByComparingTo("80.00");
        assertThat(summary.grossRevenue().amount()).isEqualByComparingTo("500.00");
        assertThat(summary.journeyProgress()).isEqualTo(50); // 500/1000
    }

    @Test
    void summaryCapsJourneyProgressAt100() {
        Instant from = NOW.minusSeconds(60);
        Instant to = NOW;
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, from, to)).thenReturn(1L);
        when(transactionRepository.countByMerchantIdAndCreatedAtBetween(MERCHANT_ID, from, to)).thenReturn(1L);
        when(transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, from, to)).thenReturn(new BigDecimal("2000.00"));
        portalProperties.setJourneyGoalAmount(new BigDecimal("1000.00"));

        assertThat(service.summary(MERCHANT_ID, null, from, to).journeyProgress()).isEqualTo(100);
    }

    @Test
    void revenueSeriesFillsAllDaysOfWindow() {
        Instant from = LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = LocalDate.of(2026, 8, 3).atStartOfDay(ZoneOffset.UTC).toInstant();
        when(transactionRepository.findApprovedByMerchantIdBetween(MERCHANT_ID, from, to))
                .thenReturn(List.of(
                        tx(LocalDate.of(2026, 8, 1).atTime(10, 0).toInstant(ZoneOffset.UTC), "150.00"),
                        tx(LocalDate.of(2026, 8, 2).atTime(15, 0).toInstant(ZoneOffset.UTC), "50.00")));

        var series = service.revenueSeries(MERCHANT_ID, null, from, to);

        assertThat(series).hasSize(3);
        assertThat(series.get(0).value().amount()).isEqualByComparingTo("150.00");
        assertThat(series.get(1).value().amount()).isEqualByComparingTo("50.00");
        assertThat(series.get(2).value().amount()).isEqualByComparingTo("0.00"); // dia sem venda preenchido
    }

    @Test
    void recentSalesReturnsNewestFirstLimited() {
        // Auditoria 2026-08-05: a query devolve limitado e ja ordenado (DESC).
        when(transactionRepository.findByMerchantId(MERCHANT_ID, 2)).thenReturn(List.of(
                tx(Instant.parse("2026-08-03T10:00:00Z"), "30.00"),
                tx(Instant.parse("2026-08-02T10:00:00Z"), "20.00")));

        var sales = service.recentSales(MERCHANT_ID, null, 2);

        assertThat(sales).hasSize(2);
        assertThat(sales.get(0).amount().amount()).isEqualByComparingTo("30.00");
        assertThat(sales.get(1).amount().amount()).isEqualByComparingTo("20.00");
        assertThat(sales.get(0).buyerIdHash()).isEqualTo("buyerhash");
    }

    @Test
    void attentionPointsReflectStatusWithdrawalsAndRisk() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(
                Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                        null, MerchantStatus.SUSPENDED, PLAN, Money.of("0.00"), true, NOW)));
        MerchantRiskSnapshot snapshot = new MerchantRiskSnapshot(
                MERCHANT_ID, NOW, Money.of("100.00"), 10, 8, 1,
                new BigDecimal("12.50"), new BigDecimal("0.00"), 0,
                true, false, false, 40);
        when(riskRepository.latestByMerchant(MERCHANT_ID)).thenReturn(Optional.of(snapshot));

        var points = service.attentionPoints(MERCHANT_ID);

        assertThat(points).extracting("type")
                .contains("refund_alert", "merchant_status", "withdrawals_blocked");
    }

    @Test
    void attentionPointsEmptyWhenMerchantHealthy() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(
                Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                        null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, NOW)));
        when(riskRepository.latestByMerchant(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThat(service.attentionPoints(MERCHANT_ID)).isEmpty();
    }
}
