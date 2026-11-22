package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.application.service.CircuitOpenAlertPublisher;
import com.foxcash.application.service.HoneypotAlertPublisher;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.config.RoutingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Roteador de cobrança Pix (Spec 91 — D4): fallback definitivo/incerto com poll
 * anti-dupla-cobrança, todos falham (exceção do último — fluxo Spec 28) e circuit
 * breaker preventivo (D5 — pula provedor OPEN/kill-switch/por-lojista).
 */
class PixProviderRouterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private PixProviderClient a;
    private PixProviderClient b;
    private RoutingPolicyResolver policyResolver;
    private PixProviderHealthRegistry health;
    private PixProviderRouter router;
    private final UUID merchantId = UUID.randomUUID();
    // Spec 98 (D4.5): mocks do honeypot — config provider (flag) + trap + alerta.
    private final com.foxcash.domain.port.out.MerchantConfigProvider configProvider =
            mock(com.foxcash.domain.port.out.MerchantConfigProvider.class);
    private final org.springframework.beans.factory.ObjectProvider<HoneypotPixProviderClient> honeypotProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
    private final HoneypotAlertPublisher honeypotAlerts = mock(HoneypotAlertPublisher.class);

    private PixProviderClient.CreateChargeResult okResult(String provider) {
        return new PixProviderClient.CreateChargeResult("E" + provider + "123", "qr-" + provider,
                "copy-" + provider, provider);
    }

    @BeforeEach
    void setUp() {
        a = mock(PixProviderClient.class);
        b = mock(PixProviderClient.class);
        Map<String, PixProviderClient> providers = new HashMap<>();
        providers.put("aPixProviderClient", a);
        providers.put("bPixProviderClient", b);

        PspProperties psp = new PspProperties();
        psp.setProvider("a");
        RoutingProperties routing = new RoutingProperties();
        routing.setEnabled(true);
        routing.setProviders("a,b");

        policyResolver = mock(RoutingPolicyResolver.class);
        when(policyResolver.resolveProviders(any())).thenReturn(List.of("a", "b"));
        when(policyResolver.resolvePolicyName(any())).thenReturn("default");

        CircuitOpenAlertPublisher alert = mock(CircuitOpenAlertPublisher.class);
        health = new PixProviderHealthRegistry(routing, Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry(), alert);
        router = new PixProviderRouter(providers, psp, routing, policyResolver, health,
                new SimpleMeterRegistry(), configProvider, honeypotProvider, honeypotAlerts);
    }

    private PixProviderClient.CreateChargeCommand command() {
        return new PixProviderClient.CreateChargeCommand(
                UUID.randomUUID(), merchantId, Money.of("10.00"), null, "cobranca", 3600);
    }

    @Test
    void primarioCriaCobrancaEGravaProvider() {
        when(a.createCharge(any())).thenReturn(okResult("a"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("a");
        verify(b, never()).createCharge(any());
    }

    @Test
    void erroDefinitivoFazFallbackImediatoParaB() {
        when(a.createCharge(any())).thenThrow(
                new PixProviderClient.PixProviderException("Pagar.me: 422"));
        when(b.createCharge(any())).thenReturn(okResult("b"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("b");
        // Definitivo: sem poll (getChargeByCode nunca chamado no A).
        verify(a, never()).getChargeByCode(any());
    }

    @Test
    void timeoutSemCobrancaFazPollNaoAchaEFallbackParaB() {
        when(a.createCharge(any())).thenThrow(
                new PixProviderClient.PixProviderTimeoutException("timeout A", null));
        when(a.getChargeByCode(any())).thenReturn(new PixProviderClient.GetChargeResult(false, null, null, null));
        when(b.createCharge(any())).thenReturn(okResult("b"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("b");
        verify(a).getChargeByCode(any()); // poll anti-dupla-cobrança executado
    }

    @Test
    void timeoutComCobrancaCriadaUsaA_semDuplaCobranca() {
        // A criou a cobrança mas respondeu incerto — o poll acha e o retry
        // idempotente (Spec 28) devolve a MESMA cobrança; B nunca é chamado.
        when(a.createCharge(any()))
                .thenThrow(new PixProviderClient.PixProviderTimeoutException("timeout A", null))
                .thenReturn(okResult("a"));
        when(a.getChargeByCode(any()))
                .thenReturn(new PixProviderClient.GetChargeResult(true, "qr-a", "copy-a", "pending"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("a");
        assertThat(result.pixCopyPaste()).isEqualTo("copy-a");
        verify(a, org.mockito.Mockito.times(2)).createCharge(any()); // 1ª falhou + retry idempotente
        verify(b, never()).createCharge(any());
    }

    @Test
    void consultaIncertaDoPollFazFallbackParaB() {
        // A respondeu incerto E a consulta também (não confirmou que não criou) →
        // não arrisca dupla cobrança: cai para B.
        when(a.createCharge(any())).thenThrow(
                new PixProviderClient.PixProviderTimeoutException("timeout A", null));
        when(a.getChargeByCode(any())).thenThrow(
                new PixProviderClient.PixProviderTimeoutException("consulta incerta", null));
        when(b.createCharge(any())).thenReturn(okResult("b"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("b");
    }

    @Test
    void todosFalhamLancaUltimaExcecao() {
        when(a.createCharge(any())).thenThrow(
                new PixProviderClient.PixProviderException("A: 400"));
        when(b.createCharge(any())).thenThrow(
                new PixProviderClient.PixProviderException("B: 500"));

        assertThatThrownBy(() -> router.createCharge(command()))
                .isInstanceOf(PixProviderClient.PixProviderException.class)
                .hasMessageContaining("B: 500");
    }

    @Test
    void circuitoAbertoPulaPrimarioEVaiDiretoAoB() {
        // 5 falhas do A na janela (threshold 5) → circuito OPEN → A é pulado.
        for (int i = 0; i < 5; i++) {
            health.recordFailure("a");
        }
        when(b.createCharge(any())).thenReturn(okResult("b"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("b");
        verify(a, never()).createCharge(any());
    }

    @Test
    void killSwitchPulaPrimario() {
        RoutingProperties.ProviderCircuit disabled = new RoutingProperties.ProviderCircuit();
        disabled.setEnabled(false);
        routingCircuitProvider("a", disabled);
        when(b.createCharge(any())).thenReturn(okResult("b"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("b");
        verify(a, never()).createCharge(any());
    }

    @Test
    void circuitoPorLojistaPulaPrimarioSoParaAqueleLojista() {
        health.openForMerchant(merchantId, "a");
        when(b.createCharge(any())).thenReturn(okResult("b"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("b");
        verify(a, never()).createCharge(any());
    }

    private void routingCircuitProvider(String provider, RoutingProperties.ProviderCircuit config) {
        // O router usa o RoutingProperties do setup — reconfigura via health direto
        // não é possível; recria o health com o kill-switch (caso específico).
        RoutingProperties props = new RoutingProperties();
        props.setEnabled(true);
        props.setProviders("a,b");
        props.getCircuitBreaker().getProviders().put(provider, config);
        // Novo router com o kill-switch configurado.
        Map<String, PixProviderClient> providers = new HashMap<>();
        providers.put("aPixProviderClient", a);
        providers.put("bPixProviderClient", b);
        PspProperties psp = new PspProperties();
        psp.setProvider("a");
        health = new PixProviderHealthRegistry(props, Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry(), mock(CircuitOpenAlertPublisher.class));
        router = new PixProviderRouter(providers, psp, props, policyResolver, health,
                new SimpleMeterRegistry(), configProvider, honeypotProvider, honeypotAlerts);
    }

    // ---------- Spec 91 critério 8 (achado do audit): fail-fast de credencial ----------

    @Test
    void provedorRealListadoSemCredencialFalhaNaConstrucao() {
        // C8: o PspProperties.validate() só cobre o provider configurado — com o
        // routing, adquirente real listado (pagarme) sem secret-key quebra o boot
        // (antes só falharia em runtime com 401 no PSP).
        Map<String, PixProviderClient> providers = new HashMap<>();
        providers.put("pagarMePixProviderClient", a);
        PspProperties psp = new PspProperties();
        psp.setProvider("mock");
        RoutingProperties routing = new RoutingProperties();
        routing.setEnabled(true);
        routing.setProviders("pagarme");
        // Sem policy (lista vazia) → usa routing.providers (não o stub ["a","b"] do setUp).
        when(policyResolver.resolveProviders(any())).thenReturn(List.of());

        assertThatThrownBy(() -> new PixProviderRouter(providers, psp, routing,
                policyResolver, health, new SimpleMeterRegistry(),
                configProvider, honeypotProvider, honeypotAlerts))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pagarme");
    }

    @Test
    void provedorRealListadoComCredencialValidaConstroi() {
        Map<String, PixProviderClient> providers = new HashMap<>();
        providers.put("pagarMePixProviderClient", a);
        PspProperties psp = new PspProperties();
        psp.setProvider("mock");
        psp.getPagarme().setSecretKey("sk_valida");
        RoutingProperties routing = new RoutingProperties();
        routing.setEnabled(true);
        routing.setProviders("pagarme");
        // Sem policy (lista vazia) → usa routing.providers (não o stub ["a","b"] do setUp).
        when(policyResolver.resolveProviders(any())).thenReturn(List.of());

        PixProviderRouter built = new PixProviderRouter(providers, psp, routing,
                policyResolver, health, new SimpleMeterRegistry(),
                configProvider, honeypotProvider, honeypotAlerts);

        assertThat(built).isNotNull();
    }

    // ---------- Spec 98 (D4.5): honeypot — trap vence qualquer policy ----------

    @Test
    void honeypotMerchantRoutesToTrapBeforeAnyProvider() {
        // Lojista em ambiente controlado: a cobrança vai para o TRAP (provider
        // "honeypot", QR fictício) ANTES de qualquer adquirente real; o alerta
        // HONEYPOT_TRIGGERED é disparado (throttled) e os provedores reais nunca
        // veem a cobrança.
        com.foxcash.domain.model.merchant.MerchantConfig honeypotConfig =
                new com.foxcash.domain.model.merchant.MerchantConfig(null, null, null, null, null,
                        null, null, null, null, null, null, false, true);
        when(configProvider.config(merchantId)).thenReturn(Optional.of(honeypotConfig));
        when(honeypotProvider.getIfAvailable()).thenReturn(new HoneypotPixProviderClient());

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("honeypot");
        assertThat(result.externalReference()).startsWith("HP");
        verify(honeypotAlerts).record(eq(merchantId), any());
        verify(a, never()).createCharge(any());
        verify(b, never()).createCharge(any());
    }

    @Test
    void nonHoneypotMerchantIgnoresTrap() {
        when(configProvider.config(merchantId)).thenReturn(Optional.of(
                com.foxcash.domain.model.merchant.MerchantConfig.defaults()));
        when(a.createCharge(any())).thenReturn(okResult("a"));

        PixProviderClient.CreateChargeResult result = router.createCharge(command());

        assertThat(result.provider()).isEqualTo("a");
        verify(honeypotProvider, never()).getIfAvailable();
        verify(honeypotAlerts, never()).record(any(), any());
    }
}
