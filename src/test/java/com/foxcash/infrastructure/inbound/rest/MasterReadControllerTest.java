package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.admin.InvoiceSearchQuery;
import com.foxcash.domain.model.admin.IpLookupResult;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.billing.InvoiceStatus;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.in.AdminDashboardUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.InvoiceResponse;
import com.foxcash.infrastructure.inbound.rest.dto.IpLookupResponse;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@code MasterReadController} (Spec 71, fatia 1) — espelhos
 * READ-ONLY de {@code /api/v1/master/merchants} e {@code /api/v1/master/lookup/ip/{ip}}:
 * mesmo shape/validações do admin, reuso do {@code AdminDashboardUseCase} e 400
 * {@code invalid_argument} em parâmetro inválido (IP incluso).
 *
 * <p>O 401/403 de rota (ROLE_MASTER) é responsabilidade do {@code MasterAuthFilter} +
 * {@code SecurityConfig} — coberto pela suíte E2E (master-console-ui.spec.js e
 * auth.spec.js B2/B3).
 */
class MasterReadControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final AdminDashboardUseCase adminDashboard = mock(AdminDashboardUseCase.class);
    private MasterReadController controller;

    @BeforeEach
    void setUp() {
        controller = new MasterReadController(adminDashboard);
    }

    private Merchant merchant() {
        PricingPlan plan = new PricingPlan(Money.of("0.50"), Money.of("69.95"));
        return Merchant.restore(MERCHANT_ID, "Loja A", "loja-a@example.com", "52998224725",
                "hash", MerchantStatus.ACTIVE, plan, Money.of("0.00"), false,
                Instant.parse("2026-08-01T10:00:00Z"));
    }

    @Test
    void merchantsDelegatesToAdminDashboardWithParsedFilters() {
        when(adminDashboard.searchMerchants(any())).thenReturn(
                PageResult.of(List.of(merchant()), 0, 20, 1));

        PageResponse<MerchantResponse> response = controller.merchants(
                "active", "loja", "2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z", 0, 20);

        ArgumentCaptor<com.foxcash.domain.model.admin.MerchantSearchQuery> captor =
                ArgumentCaptor.forClass(com.foxcash.domain.model.admin.MerchantSearchQuery.class);
        verify(adminDashboard).searchMerchants(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(captor.getValue().query()).isEqualTo("loja");
        assertThat(captor.getValue().createdFrom()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(captor.getValue().createdTo()).isEqualTo(Instant.parse("2026-08-31T23:59:59Z"));
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo(MERCHANT_ID);
        assertThat(response.items().get(0).status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void merchantsWithInvalidStatusThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.merchants("INVALIDO", null, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'status'");
    }

    @Test
    void merchantsWithInvalidFromThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.merchants(null, null, "ontem", null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'from'");
    }

    @Test
    void merchantsClampsSizeToMax100() {
        when(adminDashboard.searchMerchants(any())).thenReturn(PageResult.of(List.of(), 0, 100, 0));

        controller.merchants(null, null, null, null, 0, 500);

        ArgumentCaptor<com.foxcash.domain.model.admin.MerchantSearchQuery> captor =
                ArgumentCaptor.forClass(com.foxcash.domain.model.admin.MerchantSearchQuery.class);
        verify(adminDashboard).searchMerchants(captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(100);
    }

    @Test
    void lookupByIpDelegatesAndReturnsMatches() {
        IpLookupResult.MerchantMatch match = new IpLookupResult.MerchantMatch(MERCHANT_ID, "Loja A", "loja@example.com",
                "ACTIVE", 5L, Money.of("12.50"), Instant.parse("2026-08-01T10:00:00Z"),
                2L, Money.of("25.00"), null);
        when(adminDashboard.lookupByIp("192.168.1.10"))
                .thenReturn(new IpLookupResult("192.168.1.10", List.of(match)));

        IpLookupResponse response = controller.lookupByIp("192.168.1.10");

        assertThat(response.ip()).isEqualTo("192.168.1.10");
        assertThat(response.matches()).hasSize(1);
        assertThat(response.matches().get(0).merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(response.matches().get(0).totalSales()).isEqualTo(5L);
        verify(adminDashboard).lookupByIp("192.168.1.10");
    }

    @Test
    void lookupByIpWithoutAssociationReturnsEmptyMatches() {
        when(adminDashboard.lookupByIp("10.0.0.1"))
                .thenReturn(new IpLookupResult("10.0.0.1", List.of()));

        IpLookupResponse response = controller.lookupByIp("10.0.0.1");

        assertThat(response.matches()).isEmpty();
    }

    @Test
    void lookupByIpWithInvalidIpThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.lookupByIp("nao-e-ip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'ip'");
    }

    private Invoice invoice() {
        return Invoice.restore(UUID.randomUUID(), MERCHANT_ID, BillingPeriod.of(YearMonth.of(2026, 8)), 12,
                Money.of("6.00"), Money.of("69.95"), Money.of("75.95"),
                LocalDate.of(2026, 9, 10), InvoiceStatus.PENDING,
                Instant.parse("2026-08-31T23:59:59Z"), Money.of("0.00"), null);
    }

    @Test
    void invoicesDelegatesToAdminDashboardWithParsedFilters() {
        when(adminDashboard.searchInvoices(any())).thenReturn(
                PageResult.of(List.of(invoice()), 0, 20, 1));

        PageResponse<InvoiceResponse> response = controller.invoices(
                MERCHANT_ID.toString(), "pending", "2026-08", 0, 20);

        ArgumentCaptor<InvoiceSearchQuery> captor = ArgumentCaptor.forClass(InvoiceSearchQuery.class);
        verify(adminDashboard).searchInvoices(captor.capture());
        assertThat(captor.getValue().merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(captor.getValue().status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(captor.getValue().period().yearMonth()).isEqualTo(YearMonth.of(2026, 8));
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(response.items().get(0).status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void invoicesWithInvalidStatusThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.invoices(null, "INVALIDO", null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'status'");
    }

    @Test
    void invoicesWithInvalidBillingPeriodThrowsInvalidArgument() {
        assertThatThrownBy(() -> controller.invoices(null, null, "ontem", 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'month'");
    }

    @Test
    void invoicesClampsSizeToMax100() {
        when(adminDashboard.searchInvoices(any())).thenReturn(PageResult.of(List.of(), 0, 100, 0));

        controller.invoices(null, null, null, 0, 500);

        ArgumentCaptor<InvoiceSearchQuery> captor = ArgumentCaptor.forClass(InvoiceSearchQuery.class);
        verify(adminDashboard).searchInvoices(captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(100);
    }
}
