package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.master.MasterEarnings;
import com.foxcash.domain.model.master.MasterFees;
import com.foxcash.domain.model.master.MasterTransactionSearchQuery;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.MasterDashboardPort;
import com.foxcash.domain.port.out.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterDashboardService} (Spec 15): o master le
 * transacoes, ganhos e taxas; earnings/fees validam a existencia do lojista
 * (404). Somente-leitura.
 */
class MasterDashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MasterDashboardPort port = mock(MasterDashboardPort.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);

    private MasterDashboardService service;

    @BeforeEach
    void setUp() {
        service = new MasterDashboardService(port, merchantRepository);
    }

    private Merchant merchant() {
        return Merchant.restore(MERCHANT_ID, "Loja Teste LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("100.00"), false, NOW);
    }

    @Test
    void transactionsDelegatesToPort() {
        MasterTransactionSearchQuery query = new MasterTransactionSearchQuery(
                MERCHANT_ID, TransactionStatus.APPROVED, null, null, 0, 20);
        PageResult<PixTransaction> expected = PageResult.of(List.of(), 0, 20, 0);
        when(port.findTransactions(query)).thenReturn(expected);

        assertThat(service.transactions(query)).isSameAs(expected);
        verify(port).findTransactions(query);
    }

    @Test
    void earningsReturnsPortResult() {
        MasterEarnings earnings = new MasterEarnings(
                Money.of("100.00"), Money.of("500.00"), Money.of("0.00"), false);
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant()));
        when(port.earnings(MERCHANT_ID)).thenReturn(earnings);

        assertThat(service.earnings(MERCHANT_ID)).isSameAs(earnings);
        verify(port).earnings(MERCHANT_ID);
    }

    @Test
    void earningsUnknownMerchantThrowsNotFoundAndNeverCallsPort() {
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.earnings(MERCHANT_ID))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(port, never()).earnings(any());
    }

    @Test
    void feesReturnsPortResult() {
        MasterFees fees = new MasterFees(2, Money.of("1.00"), Money.of("69.95"), Money.of("70.95"));
        BillingPeriod period = BillingPeriod.of(YearMonth.of(2026, 7));
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant()));
        when(port.fees(MERCHANT_ID, period)).thenReturn(fees);

        assertThat(service.fees(MERCHANT_ID, period)).isSameAs(fees);
        verify(port).fees(MERCHANT_ID, period);
    }

    @Test
    void feesUnknownMerchantThrowsNotFoundAndNeverCallsPort() {
        BillingPeriod period = BillingPeriod.of(YearMonth.of(2026, 7));
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fees(MERCHANT_ID, period))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(port, never()).fees(any(), any());
    }
}
