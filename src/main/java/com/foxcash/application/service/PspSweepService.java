package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.PspBalanceProvider;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.config.SweepProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Sweep zero-balance do saldo da conta master no PSP (Spec 92): transfere o
 * excedente ({@code available − buffer}) para a conta blindada corporativa,
 * retendo um buffer para os saques dos lojistas. Exposição máxima a um freeze
 * = o que entrou desde o último sweep + buffer.
 *
 * <p>Operação de **tesouraria** (fora do domínio de saque — não cria linha em
 * {@code withdrawals}; a trilha é {@code job_runs} + métricas + o próprio PSP).
 * Idempotência por dia: o {@code withdrawalId} é um **UUID determinístico**
 * {@code UUID.nameUUIDFromBytes("sweep:"+date)} — o {@code code} do PSP é
 * derivado dele (Pagar.me code=withdrawal.id, Spec 47) e fica estável no dia →
 * replay não duplica.
 *
 * <p>Falha (balance/provider) → exceção propagada para o {@code JobExecutionService}
 * (job FAILED + alerta {@code JOB_FAILED}) + alerta específico
 * {@code SWEEP_FAILED}; o {@code SWEEP_EXPOSURE_HIGH} dispara quando a
 * exposição ultrapassa {@code foxcash.psp.sweep.exposure-alert}.
 */
@Service
public class PspSweepService {

    private static final Logger log = LoggerFactory.getLogger(PspSweepService.class);

    private final PspBalanceProvider balanceProvider;
    private final PayoutProviderClient payoutProvider;
    private final SweepProperties properties;
    private final PspProperties pspProperties;
    private final MeterRegistry meterRegistry;
    private final SweepAlertPublisher alertPublisher;

    public PspSweepService(PspBalanceProvider balanceProvider,
                           PayoutProviderClient payoutProvider,
                           SweepProperties properties,
                           PspProperties pspProperties,
                           MeterRegistry meterRegistry,
                           SweepAlertPublisher alertPublisher) {
        this.balanceProvider = balanceProvider;
        this.payoutProvider = payoutProvider;
        this.properties = properties;
        this.pspProperties = pspProperties;
        this.meterRegistry = meterRegistry;
        this.alertPublisher = alertPublisher;
    }

    /**
     * Executa o sweep. Retorna o valor transferido (R$); {@code ZERO} = skip
     * (exposição ≤ 0). Lança exceção em falha (o job captura e alerta).
     */
    public BigDecimal sweep(Instant now) {
        validateConfig();

        String provider = pspProperties.getProvider();
        PspBalanceProvider.PspBalance balance;
        try {
            balance = balanceProvider.getBalance();
        } catch (RuntimeException e) {
            alertPublisher.publishSweepFailed(provider, "balance: " + e.getMessage());
            throw e;
        }
        BigDecimal exposure = balance.available().subtract(properties.getBufferAmount());

        recordBalanceMetrics(provider, balance.available(), exposure);

        if (exposure.signum() <= 0) {
            meterRegistry.counter("foxcash_psp_sweep_total", "outcome", "skip", "provider", provider).increment();
            log.info("sweep: nada a varrer (exposure={}, available={}, buffer={})",
                    exposure, balance.available(), properties.getBufferAmount());
            return BigDecimal.ZERO;
        }

        if (exposure.compareTo(properties.getExposureAlert()) > 0) {
            alertPublisher.publishExposureHigh(exposure, properties.getExposureAlert());
        }

        // UUID determinístico por dia → code do PSP estável → replay idempotente.
        UUID sweepId = UUID.nameUUIDFromBytes(
                ("sweep:" + LocalDate.now(ZoneOffset.UTC)).getBytes());
        PayoutProviderClient.PayoutResult result;
        try {
            result = payoutProvider.createPayout(
                    new PayoutProviderClient.PayoutCommand(
                            sweepId,
                            Money.of(exposure),
                            new PayoutProviderClient.PayoutDestination(
                                    properties.getDestinationKey(), null, null, null, null),
                            "sweep diario do saldo do PSP"));
        } catch (RuntimeException e) {
            alertPublisher.publishSweepFailed(provider, "payout: " + e.getMessage());
            throw e;
        }

        meterRegistry.counter("foxcash_psp_sweep_total", "outcome", "ok", "provider", provider).increment();
        meterRegistry.counter("foxcash_psp_sweep_amount_total").increment(exposure.doubleValue());
        meterRegistry.gauge("foxcash_psp_sweep_last_timestamp", Instant.now().getEpochSecond());
        log.info("sweep: excedente transferido para conta blindada (value={}, externalReference={}, sweepId={})",
                exposure, result.externalReference(), sweepId);
        return exposure;
    }

    /** Fail-fast de config (Spec 92): destino obrigatório e buffer não-negativo. */
    private void validateConfig() {
        if (properties.getDestinationKey() == null || properties.getDestinationKey().isBlank()) {
            throw new IllegalStateException(
                    "foxcash.psp.sweep.destination-key e obrigatorio quando o sweep esta habilitado (FOXCASH_PSP_SWEEP_DESTINATION_KEY)");
        }
        if (properties.getBufferAmount() == null || properties.getBufferAmount().signum() < 0) {
            throw new IllegalStateException(
                    "foxcash.psp.sweep.buffer-amount nao pode ser negativo");
        }
    }

    private void recordBalanceMetrics(String provider, BigDecimal available, BigDecimal exposure) {
        io.micrometer.core.instrument.Gauge.builder("foxcash_psp_balance_available", available,
                        java.math.BigDecimal::doubleValue)
                .tag("provider", provider)
                .register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("foxcash_psp_balance_exposure", exposure,
                        java.math.BigDecimal::doubleValue)
                .tag("provider", provider)
                .register(meterRegistry);
    }
}
