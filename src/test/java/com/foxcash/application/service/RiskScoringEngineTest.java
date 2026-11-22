package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.domain.port.out.MerchantConfigProvider;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.RiskProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link RiskScoringEngine} (Spec 128 — Gap 2): MED 30d alto →
 * reduzir rate limit (reversível), fail-safes (kill-switch, 0 pagos, não-ACTIVE,
 * piso), override manual vence (risk_auto_managed), idempotência, auditoria
 * SYSTEM e métrica.
 */
class RiskScoringEngineTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantConfigProvider configProvider = mock(MerchantConfigProvider.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final ChargebackRepository chargebackRepository = mock(ChargebackRepository.class);
    private final RiskProperties riskProperties = new RiskProperties();
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private RiskScoringEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RiskScoringEngine(configProvider, transactionRepository, chargebackRepository,
                riskProperties, activityRecorder, meterRegistry);
    }

    private Merchant activeMerchant() {
        return Merchant.restore(MERCHANT_ID, "Loja A", null, null, null,
                MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, Instant.now());
    }

    private Merchant merchantWith(MerchantConfig config, boolean riskAutoManaged) {
        return Merchant.restore(MERCHANT_ID, "Loja A", null, null, null,
                MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, Instant.now(),
                config, riskAutoManaged);
    }

    private static MerchantConfig configWithRateLimit(Integer rps, Integer rpm) {
        return new MerchantConfig(null, null, null, null, null, null, null, null, null,
                rps, rpm, false, false);
    }

    /** MED 30d = (refunded + chargebacks) ÷ paidCount (approved + refunded) × 100. */
    private void stubMed30d(long approved, long refunded, long chargebacks) {
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                eq(MERCHANT_ID), eq(TransactionStatus.APPROVED), any(), any())).thenReturn(approved);
        when(transactionRepository.countByMerchantIdAndStatusAndCreatedAtBetween(
                eq(MERCHANT_ID), eq(TransactionStatus.REFUNDED), any(), any())).thenReturn(refunded);
        when(chargebackRepository.countByMerchantIdAndCreatedAtAfter(eq(MERCHANT_ID), any()))
                .thenReturn(chargebacks);
    }

    @Test
    void reducesRateLimitWhenMedAboveThresholdAndNoOverride() {
        Merchant merchant = activeMerchant(); // sem override
        stubMed30d(80, 20, 0); // MED 20% (100 pagas) > 10%
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        engine.evaluate(merchant);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(configProvider).save(captor.capture());
        MerchantConfig config = captor.getValue().config();
        // default-rps 5 → 5 × 0,5 = 2,5 → floor 2 (≥ piso 1); default-rpm 300 → 150.
        assertThat(config.rateLimitRps()).isEqualTo(2);
        assertThat(config.rateLimitRpm()).isEqualTo(150);
        verify(activityRecorder).record(eq(PlatformActorType.SYSTEM), isNull(),
                eq(PlatformAction.RISK_AUTO_RATE_LIMIT_REDUCED),
                eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), any());
        assertThat(meterRegistry.counter("foxcash_risk_auto_actions_total", "action", "reduced").count())
                .isEqualTo(1);
    }

    @Test
    void restoresRateLimitWhenMedNormalizesAndOverrideWasEngineSet() {
        // Override presente e flag false → foi o engine que pôs → restaura.
        Merchant merchant = merchantWith(configWithRateLimit(2, 150), false);
        stubMed30d(98, 2, 0); // MED 2% (100 pagas) <= 5% (histerese de restauração)
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        engine.evaluate(merchant);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(configProvider).save(captor.capture());
        assertThat(captor.getValue().config().rateLimitRps()).isNull();
        assertThat(captor.getValue().config().rateLimitRpm()).isNull();
        verify(activityRecorder).record(eq(PlatformActorType.SYSTEM), isNull(),
                eq(PlatformAction.RISK_AUTO_RATE_LIMIT_RESTORED),
                eq("MERCHANT"), eq(MERCHANT_ID), eq(MERCHANT_ID), any());
        assertThat(meterRegistry.counter("foxcash_risk_auto_actions_total", "action", "restored").count())
                .isEqualTo(1);
    }

    @Test
    void staysIdempotentWhileMedHighWithOverridePresent() {
        // MED continua alto mas o override já está posto (engine) → sem nova ação.
        Merchant merchant = merchantWith(configWithRateLimit(2, 150), false);
        stubMed30d(80, 20, 0);

        engine.evaluate(merchant);

        verify(configProvider, never()).findWithConfigForUpdate(any());
        verify(configProvider, never()).save(any());
    }

    @Test
    void doesNotReduceWhenMedBelowThreshold() {
        Merchant merchant = activeMerchant();
        stubMed30d(95, 5, 0); // MED 5% (100 pagas) <= 10%

        engine.evaluate(merchant);

        verify(configProvider, never()).findWithConfigForUpdate(any());
        verify(configProvider, never()).save(any());
    }

    @Test
    void skipsWhenRiskAutoManagedByHuman() {
        // Override manual vence: humano tocou → engine não age (nem reduz nem restaura).
        Merchant merchant = merchantWith(configWithRateLimit(2, 150), true);
        stubMed30d(80, 20, 0);

        engine.evaluate(merchant);

        verify(configProvider, never()).findWithConfigForUpdate(any());
        verify(configProvider, never()).save(any());
        assertThat(meterRegistry.counter("foxcash_risk_auto_actions_total", "action", "skipped_manual").count())
                .isEqualTo(1);
    }

    @Test
    void doesNotActWithZeroPaidInWindow() {
        // Fail-safe: sem vendas pagas recentes (aprovadas + estornadas) → sem ação.
        Merchant merchant = activeMerchant();
        stubMed30d(0, 0, 10); // nada pago na janela (só chargebacks sem venda)

        engine.evaluate(merchant);

        verify(configProvider, never()).findWithConfigForUpdate(any());
        verify(configProvider, never()).save(any());
    }

    @Test
    void allRefundedMerchantStillActs() {
        // Achado do smoke 2026-08-16: estornada JÁ foi paga (paidCount = refunded)
        // — um lojista com todas as vendas estornadas tem MED 100% e DEVE agir.
        Merchant merchant = activeMerchant();
        stubMed30d(0, 10, 0); // 10 estornadas, 0 aprovadas → paidCount 10 → MED 100%
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        engine.evaluate(merchant);

        verify(configProvider).save(any());
    }

    @Test
    void doesNotActWhenKillSwitchDisabled() {
        riskProperties.getAuto().setEnabled(false);
        Merchant merchant = activeMerchant();
        stubMed30d(80, 20, 0);

        engine.evaluate(merchant);

        verify(configProvider, never()).findWithConfigForUpdate(any());
        verify(configProvider, never()).save(any());
    }

    @Test
    void doesNotActWhenMerchantNotActive() {
        Merchant suspended = Merchant.restore(MERCHANT_ID, "Loja A", null, null, null,
                MerchantStatus.SUSPENDED, PLAN, Money.of("0.00"), false, Instant.now());
        stubMed30d(80, 20, 0);

        engine.evaluate(suspended);

        verify(configProvider, never()).findWithConfigForUpdate(any());
        verify(configProvider, never()).save(any());
    }

    @Test
    void reductionNeverGoesBelowFloor() {
        riskProperties.getAuto().setDefaultRps(1);
        riskProperties.getAuto().setDefaultRpm(60);
        // Redução 50%: rps 0,5 → 0; rpm 30 → 30 — o piso (1/60) garante o mínimo.
        Merchant merchant = activeMerchant();
        stubMed30d(80, 20, 0);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        engine.evaluate(merchant);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(configProvider).save(captor.capture());
        assertThat(captor.getValue().config().rateLimitRps()).isEqualTo(1);
        assertThat(captor.getValue().config().rateLimitRpm()).isEqualTo(60);
    }

    @Test
    void doesNotReduceIfOverrideAppearedUnderLock() {
        // SEC-19: o merchant do batch pode estar stale — a re-checagem sob lock
        // vê o override que apareceu no meio e não toca (alguém (re)definiu).
        Merchant batchMerchant = activeMerchant(); // snapshot sem override
        Merchant freshWithOverride = merchantWith(configWithRateLimit(9, 999), false);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(freshWithOverride));
        stubMed30d(80, 20, 0);

        engine.evaluate(batchMerchant);

        verify(configProvider, never()).save(any());
    }
}
