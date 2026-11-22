package com.foxcash.application.service;

import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.PspBalanceProvider;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.config.SweepProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PspSweepService} (Spec 92): transfere o excedente
 * (available − buffer) para a conta blindada com UUID determinístico por dia,
 * skip quando ≤ 0, fail-fast de config, alertas SWEEP_FAILED/SWEEP_EXPOSURE_HIGH.
 */
class PspSweepServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    private final PspBalanceProvider balanceProvider = mock(PspBalanceProvider.class);
    private final PayoutProviderClient payoutProvider = mock(PayoutProviderClient.class);
    private final SweepProperties properties = new SweepProperties();
    private final PspProperties pspProperties = new PspProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SweepAlertPublisher alertPublisher = mock(SweepAlertPublisher.class);
    private PspSweepService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setDestinationKey("evp-account-blindada");
        pspProperties.setProvider("pagarme");
        service = new PspSweepService(balanceProvider, payoutProvider, properties,
                pspProperties, meterRegistry, alertPublisher);
    }

    @Test
    void sweepsExcessAboveBufferToShieldedAccount() {
        when(balanceProvider.getBalance()).thenReturn(
                new PspBalanceProvider.PspBalance(new BigDecimal("1000.00"), BigDecimal.ZERO, "BRL"));
        properties.setBufferAmount(new BigDecimal("200.00"));
        when(payoutProvider.createPayout(any())).thenReturn(
                new PayoutProviderClient.PayoutResult("PO123"));

        BigDecimal transferred = service.sweep(NOW);

        assertThat(transferred).isEqualByComparingTo("800.00");
        UUID expectedId = UUID.nameUUIDFromBytes(
                ("sweep:" + LocalDate.now(ZoneOffset.UTC)).getBytes());
        verify(payoutProvider).createPayout(eq(new PayoutProviderClient.PayoutCommand(
                expectedId,
                com.foxcash.domain.model.Money.of(new BigDecimal("800.00")),
                new PayoutProviderClient.PayoutDestination("evp-account-blindada", null, null, null, null),
                "sweep diario do saldo do PSP")));
    }

    @Test
    void skipsWhenExposureNotPositive() {
        when(balanceProvider.getBalance()).thenReturn(
                new PspBalanceProvider.PspBalance(new BigDecimal("100.00"), BigDecimal.ZERO, "BRL"));
        properties.setBufferAmount(new BigDecimal("200.00"));

        BigDecimal transferred = service.sweep(NOW);

        assertThat(transferred).isEqualByComparingTo("0");
        verify(payoutProvider, never()).createPayout(any());
        assertThat(meterRegistry.counter("foxcash_psp_sweep_total", "outcome", "skip", "provider", "pagarme").count())
                .isEqualTo(1.0);
    }

    @Test
    void failsFastWithoutDestinationKey() {
        properties.setDestinationKey(null);

        assertThatThrownBy(() -> service.sweep(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destination-key");
        verify(balanceProvider, never()).getBalance();
    }

    @Test
    void publishesExposureHighWhenAboveThreshold() {
        when(balanceProvider.getBalance()).thenReturn(
                new PspBalanceProvider.PspBalance(new BigDecimal("50000.00"), BigDecimal.ZERO, "BRL"));
        properties.setBufferAmount(BigDecimal.ZERO);
        properties.setExposureAlert(new BigDecimal("10000"));
        when(payoutProvider.createPayout(any())).thenReturn(
                new PayoutProviderClient.PayoutResult("PO123"));

        service.sweep(NOW);

        verify(alertPublisher).publishExposureHigh(
                new BigDecimal("50000.00"), new BigDecimal("10000"));
    }

    @Test
    void balanceFailurePublishesSweepFailedAndRethrows() {
        RuntimeException boom = new IllegalStateException("psp fora do ar");
        when(balanceProvider.getBalance()).thenThrow(boom);

        assertThatThrownBy(() -> service.sweep(NOW)).isSameAs(boom);

        verify(alertPublisher).publishSweepFailed("pagarme", "balance: psp fora do ar");
        verify(payoutProvider, never()).createPayout(any());
    }

    @Test
    void payoutFailurePublishesSweepFailedAndRethrows() {
        when(balanceProvider.getBalance()).thenReturn(
                new PspBalanceProvider.PspBalance(new BigDecimal("1000.00"), BigDecimal.ZERO, "BRL"));
        RuntimeException boom = new PayoutProviderClient.PayoutProviderException("payout falhou");
        when(payoutProvider.createPayout(any())).thenThrow(boom);

        assertThatThrownBy(() -> service.sweep(NOW)).isSameAs(boom);

        verify(alertPublisher).publishSweepFailed("pagarme", "payout: payout falhou");
    }
}
