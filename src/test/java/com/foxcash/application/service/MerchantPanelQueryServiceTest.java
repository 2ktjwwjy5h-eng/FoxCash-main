package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.admin.MerchantSearchQuery;
import com.foxcash.domain.model.master.MerchantPanelMerchant;
import com.foxcash.domain.model.master.MerchantPanelSearchQuery;
import com.foxcash.domain.model.master.MerchantPanelTransactionQuery;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.AdminDashboardPort;
import com.foxcash.domain.port.out.MasterDashboardPort;
import com.foxcash.domain.port.out.MerchantPanelRepository;
import com.foxcash.domain.port.out.StoreRepository;
import com.foxcash.domain.service.PricingCalculator;
import com.foxcash.infrastructure.config.MasterPanelProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelDetail;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelItem;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantPanelTransaction;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantStoreResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MerchantPanelQueryService} (Spec 97): MED/conversao com 0,
 * MED com refunds+chargebacks, gateway da ultima cobranca, perfil com
 * documento mascarado, transacoes com/sem lead, stores [] e com store, 404.
 */
class MerchantPanelQueryServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-15T10:00:00Z");

    private final MerchantPanelRepository panelRepository = mock(MerchantPanelRepository.class);
    private final AdminDashboardPort adminDashboard = mock(AdminDashboardPort.class);
    private final MasterDashboardPort masterDashboard = mock(MasterDashboardPort.class);
    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final com.foxcash.domain.port.out.DeviceFingerprintRepository fingerprintRepository =
            mock(com.foxcash.domain.port.out.DeviceFingerprintRepository.class);
    private final PricingProperties pricingProperties = new PricingProperties();
    private final MasterPanelProperties panelProperties = new MasterPanelProperties();

    private MerchantPanelQueryService service;

    @BeforeEach
    void setUp() {
        service = new MerchantPanelQueryService(panelRepository, adminDashboard, masterDashboard,
                storeRepository, fingerprintRepository, new PricingCalculator(), pricingProperties,
                panelProperties);
    }

    private Merchant merchant() {
        return Merchant.restore(MERCHANT_ID, "Ramon Gontijo", "r@x.com", "52998224725",
                "hash", MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of("1234.56"), false, NOW);
    }

    private MerchantPanelMerchant panelMerchant() {
        return new MerchantPanelMerchant(MERCHANT_ID, "Ramon Gontijo", "r@x.com", "52998224725",
                MerchantStatus.ACTIVE, Money.of("0.50"), Money.of("69.95"), Money.of("1234.56"),
                "+55 11 99999-9999", NOW, MerchantConfig.defaults());
    }

    @Test
    void medPercentIsZeroWhenNoPaidPix() {
        when(adminDashboard.findMerchants(any())).thenReturn(PageResult.of(List.of(merchant()), 0, 20, 1));
        when(panelRepository.countPaid(MERCHANT_ID)).thenReturn(0L);
        when(panelRepository.countCreated(MERCHANT_ID)).thenReturn(3L);
        when(panelRepository.countRefunded(MERCHANT_ID)).thenReturn(0L);
        when(panelRepository.countChargebacks(MERCHANT_ID)).thenReturn(0L);
        when(panelRepository.lastProvider(MERCHANT_ID)).thenReturn(Optional.empty());

        PageResult<MerchantPanelItem> result = service.searchPanel(
                new MerchantPanelSearchQuery(null, null, null, null, null, 0, 20));

        assertThat(result.items()).hasSize(1);
        MerchantPanelItem item = result.items().get(0);
        assertThat(item.medPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(item.medOccurrences()).isZero();
        assertThat(item.conversionRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(item.gateway()).isNull();
    }

    @Test
    void medPercentCountsRefundsPlusChargebacksOverPaid() {
        when(adminDashboard.findMerchants(any())).thenReturn(PageResult.of(List.of(merchant()), 0, 20, 1));
        when(panelRepository.countPaid(MERCHANT_ID)).thenReturn(710L);
        when(panelRepository.countCreated(MERCHANT_ID)).thenReturn(1254L);
        when(panelRepository.countRefunded(MERCHANT_ID)).thenReturn(57L);
        when(panelRepository.countChargebacks(MERCHANT_ID)).thenReturn(5L);
        when(panelRepository.lastProvider(MERCHANT_ID)).thenReturn(Optional.of("pagarme"));

        PageResult<MerchantPanelItem> result = service.searchPanel(
                new MerchantPanelSearchQuery(null, null, null, null, null, 0, 20));

        MerchantPanelItem item = result.items().get(0);
        // MED = (57 + 5) / 710 * 100 = 8.73 (62 ocorrências)
        assertThat(item.medPercent()).isEqualByComparingTo("8.73");
        assertThat(item.medOccurrences()).isEqualTo(62L);
        // Conversão = 710 / 1254 * 100 = 56.62
        assertThat(item.conversionRate()).isEqualByComparingTo("56.62");
        assertThat(item.paidPixCount()).isEqualTo(710L);
        assertThat(item.gateway()).isEqualTo("pagarme");
        assertThat(item.withdrawalFee()).isEqualByComparingTo("2.50");
    }

    @Test
    void riskFilterLowKeepsOnlyMedBelowThreshold() {
        Merchant lowRisk = Merchant.restore(UUID.randomUUID(), "Baixo", "b@x.com", null,
                "h", MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of("0.00"), false, NOW);
        Merchant highRisk = Merchant.restore(UUID.randomUUID(), "Alto", "a@x.com", null,
                "h", MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of("0.00"), false, NOW);
        when(adminDashboard.findMerchants(any())).thenReturn(PageResult.of(List.of(lowRisk, highRisk), 0, 20, 2));
        // lowRisk: 1 pago, 0 refunds -> MED 0 (baixo). highRisk: 10 pagos, 6 refunds -> MED 60 (alto).
        when(panelRepository.countPaid(lowRisk.id())).thenReturn(1L);
        when(panelRepository.countCreated(lowRisk.id())).thenReturn(1L);
        when(panelRepository.countRefunded(lowRisk.id())).thenReturn(0L);
        when(panelRepository.countChargebacks(lowRisk.id())).thenReturn(0L);
        when(panelRepository.lastProvider(lowRisk.id())).thenReturn(Optional.empty());
        when(panelRepository.countPaid(highRisk.id())).thenReturn(10L);
        when(panelRepository.countCreated(highRisk.id())).thenReturn(10L);
        when(panelRepository.countRefunded(highRisk.id())).thenReturn(6L);
        when(panelRepository.countChargebacks(highRisk.id())).thenReturn(0L);
        when(panelRepository.lastProvider(highRisk.id())).thenReturn(Optional.empty());

        PageResult<MerchantPanelItem> result = service.searchPanel(
                new MerchantPanelSearchQuery(null, null, null, null, MerchantPanelSearchQuery.RiskLevel.LOW, 0, 20));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).name()).isEqualTo("Baixo");
    }

    @Test
    void panelDetailMasksDocumentAndExposesKpisAndFees() {
        when(panelRepository.merchantWithFees(MERCHANT_ID)).thenReturn(Optional.of(panelMerchant()));
        when(panelRepository.countPaid(MERCHANT_ID)).thenReturn(710L);
        when(panelRepository.countCreated(MERCHANT_ID)).thenReturn(1254L);
        when(panelRepository.countRefunded(MERCHANT_ID)).thenReturn(57L);
        when(panelRepository.countChargebacks(MERCHANT_ID)).thenReturn(5L);
        when(panelRepository.sumApprovedAmount(MERCHANT_ID)).thenReturn(new BigDecimal("6567.46"));
        when(panelRepository.lastProvider(MERCHANT_ID)).thenReturn(Optional.of("pagarme"));
        when(storeRepository.existsByMerchantId(MERCHANT_ID)).thenReturn(true);

        MerchantPanelDetail detail = service.panelDetail(MERCHANT_ID);

        assertThat(detail.kpis().walletBalance()).isEqualByComparingTo("1234.56");
        assertThat(detail.kpis().grossRevenue()).isEqualByComparingTo("6567.46");
        assertThat(detail.kpis().paidPixCount()).isEqualTo(710L);
        assertThat(detail.kpis().conversionRate()).isEqualByComparingTo("56.62");
        assertThat(detail.kpis().storesCount()).isEqualTo(1);
        assertThat(detail.account().documentMasked()).isEqualTo("529.***.***-25");
        assertThat(detail.account().phone()).isEqualTo("+55 11 99999-9999");
        assertThat(detail.account().gateway()).isEqualTo("pagarme");
        assertThat(detail.feesAndLimits().transactionFee()).isEqualByComparingTo("0.50");
        assertThat(detail.feesAndLimits().fixedFee()).isEqualByComparingTo("0.50");
        assertThat(detail.feesAndLimits().withdrawalFee()).isEqualByComparingTo("2.50");
        assertThat(detail.feesAndLimits().medPercent()).isEqualByComparingTo("8.73");
        assertThat(detail.feesAndLimits().medOccurrences()).isEqualTo(62L);
        assertThat(detail.feesAndLimits().pixMin()).isNull();
        assertThat(detail.feesAndLimits().withdrawalMax()).isNull();
    }

    @Test
    void panelDetailThrows404WhenMerchantMissing() {
        when(panelRepository.merchantWithFees(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.panelDetail(MERCHANT_ID))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    void transactionsResolveBuyerViaLeadAndNetAmount() {
        when(panelRepository.merchantWithFees(MERCHANT_ID)).thenReturn(Optional.of(panelMerchant()));
        UUID botId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        PixTransaction tx = PixTransaction.restore(UUID.randomUUID(), MERCHANT_ID,
                Money.of("4.89"), "VIP Semanal", null, "e2e", "E123", null, null,
                TransactionStatus.APPROVED, NOW, NOW, NOW, "a1b2", null, planId, botId,
                com.foxcash.domain.model.plan.Platform.TELEGRAM, "user-1", null, null, null);
        when(masterDashboard.findTransactions(any())).thenReturn(
                PageResult.of(List.of(tx), 0, 20, 1));
        when(panelRepository.buyerName(botId, "user-1")).thenReturn(Optional.of("João da Silva"));
        when(panelRepository.planName(planId)).thenReturn(Optional.of("VIP Semanal"));

        PageResult<MerchantPanelTransaction> result = service.transactions(MERCHANT_ID,
                new MerchantPanelTransactionQuery(null, null, null, 0, 20));

        MerchantPanelTransaction item = result.items().get(0);
        assertThat(item.buyerName()).isEqualTo("João da Silva");
        assertThat(item.productName()).isEqualTo("VIP Semanal");
        assertThat(item.externalReference()).isEqualTo("E123");
        assertThat(item.fee()).isEqualByComparingTo("0.50");
        // líquido = 4.89 - 0.50 = 4.39
        assertThat(item.netAmount()).isEqualByComparingTo("4.39");
    }

    @Test
    void transactionsWithoutLeadKeepBuyerIdHashOnly() {
        when(panelRepository.merchantWithFees(MERCHANT_ID)).thenReturn(Optional.of(panelMerchant()));
        PixTransaction tx = PixTransaction.restore(UUID.randomUUID(), MERCHANT_ID,
                Money.of("4.89"), "avulsa", null, "e2e", "E124", null, null,
                TransactionStatus.APPROVED, NOW, NOW, NOW, "opaque-hash", null, null, null,
                null, null, null, null, null);
        when(masterDashboard.findTransactions(any())).thenReturn(
                PageResult.of(List.of(tx), 0, 20, 1));

        PageResult<MerchantPanelTransaction> result = service.transactions(MERCHANT_ID,
                new MerchantPanelTransactionQuery(null, null, null, 0, 20));

        assertThat(result.items().get(0).buyerName()).isNull();
        assertThat(result.items().get(0).buyerIdHash()).isEqualTo("opaque-hash");
        assertThat(result.items().get(0).productName()).isNull();
    }

    @Test
    void transactionsThrows404WhenMerchantMissing() {
        when(panelRepository.merchantWithFees(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transactions(MERCHANT_ID,
                new MerchantPanelTransactionQuery(null, null, null, 0, 20)))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    void storesReturnsEmptyListWhenNoStore() {
        when(panelRepository.merchantWithFees(MERCHANT_ID)).thenReturn(Optional.of(panelMerchant()));
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThat(service.stores(MERCHANT_ID)).isEmpty();
    }

    @Test
    void storesReturnsStoreWithoutSocialLinks() {
        when(panelRepository.merchantWithFees(MERCHANT_ID)).thenReturn(Optional.of(panelMerchant()));
        Store store = Store.restore(UUID.randomUUID(), MERCHANT_ID, "Loja X", null, null,
                "desc", List.of(new Store.SocialLink("discord", "https://discord.gg/abc")),
                "#FF0000", "🔥", "Título", "rodapé", NOW, NOW);
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(store));

        List<MerchantStoreResponse> result = service.stores(MERCHANT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Loja X");
        assertThat(result.get(0).title()).isEqualTo("Título");
        assertThat(result.get(0).color()).isEqualTo("#FF0000");
        assertThat(result.get(0).emoji()).isEqualTo("🔥");
    }

    @Test
    void storesThrows404WhenMerchantMissing() {
        when(panelRepository.merchantWithFees(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stores(MERCHANT_ID))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    // ---------- retomada (Spec 97/98): boundary LOW exato + taxa efetiva do painel ----------

    @Test
    void riskFilterLowIncludesMerchantExactlyAtLowThreshold() {
        // L183 (ConditionalsBoundaryMutator `med.compareTo(low) <= 0` → `< 0`):
        // MED exatamente IGUAL ao threshold baixo (default 2.0) é LOW (<=) — o
        // mutante excluiria o lojista que está EXATAMENTE no limite.
        Merchant boundary = Merchant.restore(UUID.randomUUID(), "Limite", "l@x.com", null,
                "h", MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of("0.00"), false, NOW);
        when(adminDashboard.findMerchants(any())).thenReturn(PageResult.of(List.of(boundary), 0, 20, 1));
        // 2 refunds / 100 pagos = MED 2.00 (== lowPercent default 2.0).
        when(panelRepository.countPaid(boundary.id())).thenReturn(100L);
        when(panelRepository.countCreated(boundary.id())).thenReturn(100L);
        when(panelRepository.countRefunded(boundary.id())).thenReturn(2L);
        when(panelRepository.countChargebacks(boundary.id())).thenReturn(0L);
        when(panelRepository.lastProvider(boundary.id())).thenReturn(Optional.empty());

        PageResult<MerchantPanelItem> result = service.searchPanel(
                new MerchantPanelSearchQuery(null, null, null, null, MerchantPanelSearchQuery.RiskLevel.LOW, 0, 20));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).medPercent()).isEqualByComparingTo("2.00");
    }

    @Test
    void panelItemExposesEffectiveTransactionFee() {
        // L237 (NullReturnValsMutator): effectiveTransactionFee(Merchant)
        // retornaria null — a taxa exibida no painel (sem override → taxa do
        // plano) não pode sumir da listagem.
        when(adminDashboard.findMerchants(any())).thenReturn(PageResult.of(List.of(merchant()), 0, 20, 1));
        when(panelRepository.countPaid(MERCHANT_ID)).thenReturn(1L);
        when(panelRepository.countCreated(MERCHANT_ID)).thenReturn(1L);
        when(panelRepository.countRefunded(MERCHANT_ID)).thenReturn(0L);
        when(panelRepository.countChargebacks(MERCHANT_ID)).thenReturn(0L);
        when(panelRepository.lastProvider(MERCHANT_ID)).thenReturn(Optional.empty());

        PageResult<MerchantPanelItem> result = service.searchPanel(
                new MerchantPanelSearchQuery(null, null, null, null, null, 0, 20));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).transactionFee()).isEqualByComparingTo("0.50");
    }
}
