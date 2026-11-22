package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.service.PricingCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MerchantSalesService} (Spec 25): listagem paginada com
 * líquido (bruto − taxa), métricas com janela e conversão, clamp de dias e
 * detalhe (payload Pix só PENDING, 403 outro lojista, 404 inexistente).
 */
class MerchantSalesServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private MerchantSalesService service;

    @BeforeEach
    void setUp() {
        service = new MerchantSalesService(
                transactionRepository, merchantRepository, new PricingCalculator(), clock);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(
                Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                        null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, NOW)));
    }

    private PixTransaction tx(TransactionStatus status, String amount, String buyerIdHash) {
        return PixTransaction.restore(UUID.randomUUID(), MERCHANT_ID, Money.of(amount),
                "Cobranca", "pixkey", "REF", "REF", "qr", "copy", status,
                NOW, NOW, status == TransactionStatus.APPROVED ? NOW : null, buyerIdHash);
    }

    @Test
    void listReturnsSalesWithNetAmountAndPassesFilters() {
        Instant from = NOW.minusSeconds(3600);
        Instant to = NOW;
        when(transactionRepository.searchSales(MERCHANT_ID, null, TransactionStatus.APPROVED,
                from, to, 0, 20)).thenReturn(PageResult.of(List.of(
                tx(TransactionStatus.APPROVED, "150.00", "buyerhash"),
                tx(TransactionStatus.APPROVED, "99.90", null)), 0, 20, 5));

        var page = service.list(MERCHANT_ID, null, TransactionStatus.APPROVED, from, to, 0, 20);

        assertThat(page.items()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.items().get(0).netAmount().amount()).isEqualByComparingTo("149.50"); // 150 − 0,50
        assertThat(page.items().get(0).buyerIdHash()).isEqualTo("buyerhash");
        assertThat(page.items().get(1).netAmount().amount()).isEqualByComparingTo("99.40"); // 99,90 − 0,50
        assertThat(page.items().get(1).buyerIdHash()).isNull(); // bot não enviou buyerId
        verify(transactionRepository).searchSales(MERCHANT_ID, null, TransactionStatus.APPROVED, from, to, 0, 20);
    }

    @Test
    void metricsComputesVolumeCountsAndConversion() {
        Instant from = NOW.minusSeconds(30L * 24 * 60 * 60);
        when(transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, from, NOW))
                .thenReturn(new BigDecimal("500.00"));
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED, from, NOW)).thenReturn(80L);
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.PENDING, from, NOW)).thenReturn(20L);
        when(transactionRepository.countByMerchantIdAndCreatedAtBetween(MERCHANT_ID, from, NOW))
                .thenReturn(100L);

        var metrics = service.metrics(MERCHANT_ID, null, 30);

        assertThat(metrics.volume30d()).isEqualByComparingTo("500.00");
        assertThat(metrics.completedSales()).isEqualTo(80);
        assertThat(metrics.pendingSales()).isEqualTo(20);
        assertThat(metrics.conversionRate()).isEqualByComparingTo("80.00"); // 80/100
    }

    @Test
    void metricsClampsDaysWindowBetween1And90() {
        when(transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                eq(MERCHANT_ID), eq(TransactionStatus.APPROVED), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any(), any())).thenReturn(0L);
        when(transactionRepository.countByMerchantIdAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any())).thenReturn(0L);

        service.metrics(MERCHANT_ID, null, 120); // clamp 90
        verify(transactionRepository).sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED,
                NOW.minusSeconds(90L * 24 * 60 * 60), NOW);

        service.metrics(MERCHANT_ID, null, 0); // clamp mínimo 1
        verify(transactionRepository).sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                MERCHANT_ID, TransactionStatus.APPROVED,
                NOW.minusSeconds(24L * 60 * 60), NOW);
    }

    @Test
    void metricsZeroConversionWithoutTransactions() {
        when(transactionRepository.sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
                eq(MERCHANT_ID), eq(TransactionStatus.APPROVED), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any(), any())).thenReturn(0L);
        when(transactionRepository.countByMerchantIdAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any())).thenReturn(0L);

        var metrics = service.metrics(MERCHANT_ID, null, 30);

        assertThat(metrics.volume30d()).isEqualByComparingTo("0.00");
        assertThat(metrics.completedSales()).isZero();
        assertThat(metrics.pendingSales()).isZero();
        assertThat(metrics.conversionRate()).isEqualByComparingTo("0.00");
    }

    @Test
    void detailReturnsPixPayloadWhenPending() {
        PixTransaction pending = tx(TransactionStatus.PENDING, "150.00", "buyerhash");
        when(transactionRepository.findById(pending.id())).thenReturn(Optional.of(pending));

        var detail = service.detail(MERCHANT_ID, null, pending.id());

        assertThat(detail.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(detail.netAmount().amount()).isEqualByComparingTo("149.50");
        assertThat(detail.buyerIdHash()).isEqualTo("buyerhash");
        assertThat(detail.paidAt()).isNull();
        assertThat(detail.description()).isEqualTo("Cobranca");
        assertThat(detail.pixKey()).isEqualTo("pixkey");
        assertThat(detail.pixQrCode()).isEqualTo("qr");
        assertThat(detail.pixCopyPaste()).isEqualTo("copy");
        assertThat(detail.endToEndId()).isEqualTo("REF");
    }

    @Test
    void detailOmitsPixPayloadWhenApproved() {
        PixTransaction approved = tx(TransactionStatus.APPROVED, "150.00", "buyerhash");
        when(transactionRepository.findById(approved.id())).thenReturn(Optional.of(approved));

        var detail = service.detail(MERCHANT_ID, null, approved.id());

        assertThat(detail.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(detail.paidAt()).isEqualTo(NOW);
        assertThat(detail.description()).isNull();
        assertThat(detail.pixKey()).isNull();
        assertThat(detail.pixQrCode()).isNull();
        assertThat(detail.pixCopyPaste()).isNull();
        assertThat(detail.endToEndId()).isNull();
    }

    @Test
    void detailThrows403ForOtherMerchant() {
        PixTransaction foreign = PixTransaction.restore(UUID.randomUUID(), OTHER_MERCHANT_ID,
                Money.of("150.00"), "Cobranca", "pixkey", "REF", "REF",
                "qr", "copy", TransactionStatus.PENDING, NOW, NOW, null, "buyerhash");
        when(transactionRepository.findById(foreign.id())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.detail(MERCHANT_ID, null, foreign.id()))
                .isInstanceOf(MerchantAccessDeniedException.class);
    }

    @Test
    void detailThrows404WhenNotFound() {
        UUID unknown = UUID.randomUUID();
        when(transactionRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(MERCHANT_ID, null, unknown))
                .isInstanceOf(TransactionNotFoundException.class);
    }
}
