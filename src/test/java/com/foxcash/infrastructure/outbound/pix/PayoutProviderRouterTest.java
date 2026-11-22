package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PayoutProviderRouter} (Spec 92 — fallback simples por
 * prioridade): A 5xx/timeout → tenta B; erro definitivo propaga; todos falham →
 * última exceção; getPayout multi-get; fail-fast de config.
 */
class PayoutProviderRouterTest {

    private final PayoutProviderClient a = mock(PayoutProviderClient.class);
    private final PayoutProviderClient b = mock(PayoutProviderClient.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final PayoutProviderClient.PayoutCommand cmd =
            new PayoutProviderClient.PayoutCommand(UUID.randomUUID(), Money.of("100.00"),
                    new PayoutProviderClient.PayoutDestination("evp", null, null, null, null), "teste");

    private PayoutProviderRouter router(String providers) {
        PspProperties props = new PspProperties();
        props.setPayoutRoutingEnabled(true);
        props.setPayoutRoutingProviders(providers);
        Map<String, PayoutProviderClient> beans = new LinkedHashMap<>();
        beans.put("mockPayoutProviderClient", a);
        beans.put("pagarMePayoutProviderClient", b);
        return new PayoutProviderRouter(beans, props, meterRegistry);
    }

    @Test
    void fallsBackFromAWhenProviderFails() {
        when(a.createPayout(cmd)).thenThrow(new PayoutProviderClient.PayoutProviderException("A 5xx"));
        when(b.createPayout(cmd)).thenReturn(new PayoutProviderClient.PayoutResult("PO-B"));

        PayoutProviderClient.PayoutResult result = router("mock,pagarMe").createPayout(cmd);

        assertThat(result.externalReference()).isEqualTo("PO-B");
        verify(a).createPayout(cmd);
        verify(b).createPayout(cmd);
    }

    @Test
    void stampsWinningProviderOnResult() {
        // Spec 92 (roteamento pós-fallback): o provedor que ACEITOU é carregado
        // no resultado — o caller persiste em withdrawals.payout_provider.
        when(a.createPayout(cmd)).thenThrow(new PayoutProviderClient.PayoutProviderException("A 5xx"));
        when(b.createPayout(cmd)).thenReturn(new PayoutProviderClient.PayoutResult("PO-B"));

        PayoutProviderClient.PayoutResult result = router("mock,pagarMe").createPayout(cmd);

        assertThat(result.externalReference()).isEqualTo("PO-B");
        assertThat(result.provider()).isEqualTo("pagarme");
    }

    @Test
    void fallsBackFromAOnTimeout() {
        when(a.createPayout(cmd)).thenThrow(
                new PayoutProviderClient.PayoutProviderTimeoutException("A timeout", null));
        when(b.createPayout(cmd)).thenReturn(new PayoutProviderClient.PayoutResult("PO-B"));

        PayoutProviderClient.PayoutResult result = router("mock,pagarMe").createPayout(cmd);

        assertThat(result.externalReference()).isEqualTo("PO-B");
    }

    @Test
    void definitiveErrorPropagatesWithoutFallback() {
        when(a.createPayout(cmd)).thenThrow(new IllegalArgumentException("chave invalida"));

        assertThatThrownBy(() -> router("mock,pagarMe").createPayout(cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chave invalida");
        verify(b, never()).createPayout(any());
    }

    @Test
    void allProvidersFailThrowsLast() {
        when(a.createPayout(cmd)).thenThrow(new PayoutProviderClient.PayoutProviderException("A down"));
        when(b.createPayout(cmd)).thenThrow(new PayoutProviderClient.PayoutProviderException("B down"));

        assertThatThrownBy(() -> router("mock,pagarMe").createPayout(cmd))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class)
                .hasMessageContaining("B down");
    }

    @Test
    void getPayoutMultiGetFindsRecognizingProvider() {
        when(a.getPayout("REF-B")).thenThrow(new PayoutProviderClient.PayoutProviderException("nao reconhecido"));
        when(b.getPayout("REF-B")).thenReturn(PayoutProviderClient.PayoutStatus.PAID);

        assertThat(router("mock,pagarMe").getPayout("REF-B")).isEqualTo(PayoutProviderClient.PayoutStatus.PAID);
    }

    @Test
    void disabledRoutingUsesSingleConfiguredProvider() {
        PspProperties props = new PspProperties();
        props.setPayoutProvider("mock"); // routing desabilitado (default)
        when(a.createPayout(cmd)).thenReturn(new PayoutProviderClient.PayoutResult("PO-A"));
        Map<String, PayoutProviderClient> beans = new LinkedHashMap<>();
        beans.put("mockPayoutProviderClient", a);
        beans.put("pagarMePayoutProviderClient", b);
        PayoutProviderRouter router = new PayoutProviderRouter(beans, props, meterRegistry);

        router.createPayout(cmd);

        verify(a).createPayout(cmd);
        verify(b, never()).createPayout(any());
    }

    @Test
    void enabledRoutingWithMissingProviderFailsFast() {
        PspProperties props = new PspProperties();
        props.setPayoutRoutingEnabled(true);
        props.setPayoutRoutingProviders("mock,asaas"); // asaas não tem bean
        Map<String, PayoutProviderClient> beans = new LinkedHashMap<>();
        beans.put("mockPayoutProviderClient", a);

        assertThatThrownBy(() -> new PayoutProviderRouter(beans, props, meterRegistry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asaas");
    }
}
