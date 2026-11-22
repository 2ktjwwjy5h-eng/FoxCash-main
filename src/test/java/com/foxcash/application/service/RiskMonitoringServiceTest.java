package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.RiskRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.RiskProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link RiskMonitoringService} (Spec 16 + Spec 19): indicadores
 * calculados sobre transacoes + chargebacks registrados, score 0-100 e
 * auto-suspensao por chargeback no limiar duro (desligavel).
 */
class RiskMonitoringServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final ChargebackRepository chargebackRepository = mock(ChargebackRepository.class);
    private final RiskRepository riskRepository = mock(RiskRepository.class);
    private final RiskProperties riskProperties = new RiskProperties();
    private final RiskScoringEngine riskScoringEngine = mock(RiskScoringEngine.class);

    private RiskMonitoringService service;

    @BeforeEach
    void setUp() {
        service = new RiskMonitoringService(
                merchantRepository, transactionRepository, chargebackRepository, riskRepository,
                riskProperties, riskScoringEngine);
        when(chargebackRepository.countByMerchantId(any())).thenReturn(0L);
    }

    private Merchant merchant() {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, NOW);
    }

    private void stubCounts(long total, long approved, long refunded) {
        when(transactionRepository.countByMerchantId(any())).thenReturn(total);
        when(transactionRepository.countByMerchantIdAndStatus(any(), any())).thenReturn(approved);
        when(transactionRepository.countByMerchantIdAndStatus(any(),
                eq(com.foxcash.domain.model.transaction.TransactionStatus.REFUNDED))).thenReturn(refunded);
        when(transactionRepository.sumAmountByMerchantIdAndStatus(any(), any()))
                .thenReturn(new BigDecimal("500.00"));
    }

    /** Stub da varredura paginada (auditoria 2026-08-05/M-A3): uma única página. */
    private void stubMerchantsPage(List<Merchant> merchants) {
        when(merchantRepository.findAll(anyInt(), anyInt()))
                .thenReturn(PageResult.of(merchants, 0, 200, merchants.size()));
    }

    @Test
    void savesOneSnapshotPerMerchant() {
        Merchant other = Merchant.restore(UUID.randomUUID(), "Loja B LTDA", null, "11144477735",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, NOW);
        stubMerchantsPage(List.of(merchant(), other));
        stubCounts(0, 0, 0);

        int saved = service.computeForAllMerchants();

        assertThat(saved).isEqualTo(2);
        verify(riskRepository, times(2)).save(any(MerchantRiskSnapshot.class));
    }

    @Test
    void refundRateComputedAsRefundedOverTotalAndFlagsAlertAboveThreshold() {
        stubMerchantsPage(List.of(merchant()));
        // 10 reembolsadas de 100 = 10,00% > limiar de 5% → alerta.
        stubCounts(100, 80, 10);

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        MerchantRiskSnapshot snapshot = captor.getValue();
        assertThat(snapshot.refundRatePercent()).isEqualByComparingTo("10.00");
        assertThat(snapshot.refundAlert()).isTrue();
        assertThat(snapshot.totalTransactions()).isEqualTo(100);
        // L137: refundAlert soma +25 ao score (mutante -25 daria score negativo).
        assertThat(snapshot.riskScore()).isEqualTo(25);
    }

    @Test
    void belowThresholdNoAlert() {
        stubMerchantsPage(List.of(merchant()));
        // 3 reembolsadas de 100 = 3,00% ≤ 5% → sem alerta.
        stubCounts(100, 95, 3);

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        assertThat(captor.getValue().refundRatePercent()).isEqualByComparingTo("3.00");
        assertThat(captor.getValue().refundAlert()).isFalse();
    }

    @Test
    void refundRateExactlyAtThresholdHasNoAlert() {
        // L90 boundary (> 0): 5 de 100 = 5,00% == limiar → SEM alerta (compareTo == 0).
        stubMerchantsPage(List.of(merchant()));
        stubCounts(100, 95, 5);

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        assertThat(captor.getValue().refundRatePercent()).isEqualByComparingTo("5.00");
        assertThat(captor.getValue().refundAlert()).isFalse();
    }

    @Test
    void chargebackRateExactlyAtAlertThresholdHasNoAlert() {
        // L91 boundary (> 0): 1 chargeback de 100 = 1,00% == limiar → SEM alerta.
        stubMerchantsPage(List.of(merchant()));
        stubCounts(100, 99, 0);
        when(chargebackRepository.countByMerchantId(MERCHANT_ID)).thenReturn(1L);

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        assertThat(captor.getValue().chargebackRatePercent()).isEqualByComparingTo("1.00");
        assertThat(captor.getValue().chargebackAlert()).isFalse();
    }

    @Test
    void noTransactionsYieldsZeroRateWithoutAlert() {
        stubMerchantsPage(List.of(merchant()));
        stubCounts(0, 0, 0);

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        assertThat(captor.getValue().refundRatePercent()).isEqualByComparingTo("0.00");
        assertThat(captor.getValue().refundAlert()).isFalse();
    }

    @Test
    void approvedVolumeIsSummedFromApprovedTransactions() {
        stubMerchantsPage(List.of(merchant()));
        stubCounts(10, 10, 0);

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        assertThat(captor.getValue().approvedVolume().amount()).isEqualByComparingTo("500.00");
        assertThat(captor.getValue().approvedTransactions()).isEqualTo(10);
    }

    @Test
    void chargebackWithoutRegisteredDisputesIsZero() {
        stubMerchantsPage(List.of(merchant()));
        stubCounts(10, 10, 0);

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        assertThat(captor.getValue().chargebackRatePercent()).isEqualByComparingTo("0.00");
        assertThat(captor.getValue().chargebackAlert()).isFalse();
        assertThat(captor.getValue().complaintsAlert()).isFalse();
        assertThat(captor.getValue().riskScore()).isZero();
    }

    @Test
    void chargebackRateComesFromRegisteredChargebacksAndRaisesScore() {
        stubMerchantsPage(List.of(merchant()));
        stubCounts(100, 96, 0);
        // 4 chargebacks de 100 = 4,00% > 1% (alerta) e >= 3% (limiar duro).
        when(chargebackRepository.countByMerchantId(MERCHANT_ID)).thenReturn(4L);

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        MerchantRiskSnapshot snapshot = captor.getValue();
        assertThat(snapshot.chargebackRatePercent()).isEqualByComparingTo("4.00");
        assertThat(snapshot.chargebackAlert()).isTrue();
        // chargebackAlert (40) + chargeback >= suspensao (35) = 75.
        assertThat(snapshot.riskScore()).isEqualTo(75);
    }

    @Test
    void merchantWithHardChargebackThresholdIsAutoSuspended() {
        Merchant active = merchant();
        stubMerchantsPage(List.of(active));
        stubCounts(100, 96, 0);
        when(chargebackRepository.countByMerchantId(MERCHANT_ID)).thenReturn(5L); // 5,00% >= 3%
        // Spec 128: default do auto-suspend é false — o teste reativa explicitamente.
        riskProperties.setAutoSuspendEnabled(true);
        // SEC-19: o auto-suspend relê o merchant com FOR UPDATE (estado fresco).
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(active));

        service.computeForAllMerchants();

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(MerchantStatus.SUSPENDED);
    }

    @Test
    void autoSuspendCanBeDisabled() {
        Merchant active = merchant();
        stubMerchantsPage(List.of(active));
        stubCounts(100, 96, 0);
        when(chargebackRepository.countByMerchantId(MERCHANT_ID)).thenReturn(5L);
        riskProperties.setAutoSuspendEnabled(false);

        service.computeForAllMerchants();

        verify(merchantRepository, times(0)).save(any(Merchant.class));
    }

    @Test
    void alreadySuspendedMerchantIsNotTouched() {
        Merchant suspended = Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.SUSPENDED, PLAN, Money.of("0.00"), false, NOW);
        stubMerchantsPage(List.of(suspended));
        stubCounts(100, 96, 0);
        when(chargebackRepository.countByMerchantId(MERCHANT_ID)).thenReturn(5L);

        service.computeForAllMerchants();

        verify(merchantRepository, times(0)).save(any(Merchant.class));
    }

    @Test
    void computeForAllMerchantsProcessesAllPages() {
        // L74: o loop paginado NÃO pode parar na 1ª página quando há mais páginas
        // (mutante nega `!batch.items().isEmpty()` → pararia após a página 0).
        Merchant m1 = merchant();
        Merchant m2 = Merchant.restore(UUID.randomUUID(), "Loja B LTDA", null, "11144477735",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, NOW);
        when(merchantRepository.findAll(anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(m1), 0, 200, 201))
                .thenReturn(PageResult.of(List.of(m2), 1, 200, 201));
        stubCounts(0, 0, 0);

        int saved = service.computeForAllMerchants();

        assertThat(saved).isEqualTo(2);
        verify(riskRepository, times(2)).save(any(MerchantRiskSnapshot.class));
        verify(merchantRepository, times(2)).findAll(anyInt(), anyInt());
    }

    @Test
    void chargebackExactlyAtSuspendThresholdScores75AndAutoSuspends() {
        // L142 + L126 (boundary >=): chargebackRate == 3,00% == limiar duro →
        // compareTo == 0 → `>= 0` é true → +35 no score E auto-suspensão.
        Merchant active = merchant();
        stubMerchantsPage(List.of(active));
        stubCounts(100, 96, 0);
        when(chargebackRepository.countByMerchantId(MERCHANT_ID)).thenReturn(3L); // 3,00%
        // Spec 128: default do auto-suspend é false — o teste reativa explicitamente.
        riskProperties.setAutoSuspendEnabled(true);
        // SEC-19: auto-suspend relê com FOR UPDATE.
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(active));

        service.computeForAllMerchants();

        ArgumentCaptor<MerchantRiskSnapshot> captor = ArgumentCaptor.forClass(MerchantRiskSnapshot.class);
        verify(riskRepository).save(captor.capture());
        assertThat(captor.getValue().chargebackRatePercent()).isEqualByComparingTo("3.00");
        // chargebackAlert (40) + chargeback == suspensão (35) = 75 (mutante daria 40).
        assertThat(captor.getValue().riskScore()).isEqualTo(75);
        verify(merchantRepository).save(any(Merchant.class));
    }

    // ---------- SEC-19 (revisão de segurança 2026-08-11): save sem lock não sobrescreve movimentação ----------

    @Test
    void autoSuspendReloadsFreshWithLockAndPreservesWalletBalance() {
        // SEC-19: o merchant do batch (findById) é snapshot — o save grava TODOS
        // os campos (incluindo wallet_balance). O auto-suspend relê com FOR
        // UPDATE e salva o estado FRESCO (não reverte débito/crédito concorrente).
        Merchant active = merchant();
        stubMerchantsPage(List.of(active)); // snapshot do batch (saldo 0.00 stale)
        stubCounts(100, 96, 0);
        when(chargebackRepository.countByMerchantId(MERCHANT_ID)).thenReturn(5L); // 5,00% >= 3%
        // Spec 128: default do auto-suspend é false — o teste reativa explicitamente.
        riskProperties.setAutoSuspendEnabled(true);
        // Banco (estado fresco sob FOR UPDATE) tem saldo 150.00 (pós-crédito).
        Merchant fresh = Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("150.00"), false, NOW);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(fresh));

        service.computeForAllMerchants();

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        // O saldo FRESCO (150.00) é preservado — não o 0.00 da snapshot stale.
        assertThat(captor.getValue().walletBalance().amount()).isEqualByComparingTo("150.00");
        assertThat(captor.getValue().status()).isEqualTo(MerchantStatus.SUSPENDED);
    }
}
