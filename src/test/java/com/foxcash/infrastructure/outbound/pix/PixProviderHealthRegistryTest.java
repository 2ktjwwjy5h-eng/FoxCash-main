package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.application.service.CircuitOpenAlertPublisher;
import com.foxcash.infrastructure.config.RoutingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Circuit breaker preventivo (Spec 91 — D5): janela de falhas → OPEN, cooldown →
 * HALF-OPEN (1 probe; sucesso fecha, falha reabre), kill-switch por provedor,
 * abertura por lojista (chargeback) e alerta {@code PROVIDER_CIRCUIT_OPEN}.
 */
class PixProviderHealthRegistryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** Clock mutável para testar janela/cooldown sem dormir. */
    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private final CircuitOpenAlertPublisher alertPublisher = mock(CircuitOpenAlertPublisher.class);

    private RoutingProperties routing(int threshold, long cooldownSeconds) {
        RoutingProperties props = new RoutingProperties();
        props.getCircuitBreaker().setFailureThreshold(threshold);
        props.getCircuitBreaker().setWindowSeconds(60);
        props.getCircuitBreaker().setCooldownSeconds(cooldownSeconds);
        return props;
    }

    private PixProviderHealthRegistry registry(RoutingProperties props, Clock clock) {
        return new PixProviderHealthRegistry(props, clock, new SimpleMeterRegistry(), alertPublisher);
    }

    @Test
    void abreAposLimiarDeFalhasNaJanela() {
        PixProviderHealthRegistry registry = registry(routing(3, 300), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(registry.shouldSkip("pagarme")).isFalse();
        registry.recordFailure("pagarme");
        registry.recordFailure("pagarme");
        assertThat(registry.shouldSkip("pagarme")).isFalse();
        registry.recordFailure("pagarme"); // 3ª falha → OPEN

        assertThat(registry.shouldSkip("pagarme")).isTrue();
        verify(alertPublisher).publishCircuitOpened("pagarme", "janela de falhas atingida (3 em 60s)");
    }

    @Test
    void cooldownExpiraEHalfOpenDeixaUmProbePassar() {
        MutableClock clock = new MutableClock(NOW);
        PixProviderHealthRegistry registry = registry(routing(2, 300), clock);
        registry.recordFailure("asaas");
        registry.recordFailure("asaas"); // OPEN
        assertThat(registry.shouldSkip("asaas")).isTrue();

        // Cooldown (300s) expira → HALF-OPEN: deixa passar (a próxima tentativa decide).
        clock.advanceSeconds(301);
        assertThat(registry.shouldSkip("asaas")).isFalse();

        // Falha no HALF-OPEN → reabre o circuito (OPEN + cooldown novo).
        registry.recordFailure("asaas");
        assertThat(registry.shouldSkip("asaas")).isTrue();
    }

    @Test
    void halfOpenSucessoFechaOCircuito() {
        MutableClock clock = new MutableClock(NOW);
        PixProviderHealthRegistry registry = registry(routing(2, 300), clock);
        registry.recordFailure("mock");
        registry.recordFailure("mock"); // OPEN
        clock.advanceSeconds(301);
        assertThat(registry.shouldSkip("mock")).isFalse(); // probe
        registry.recordSuccess("mock"); // probe ok → CLOSED

        assertThat(registry.shouldSkip("mock")).isFalse();
        registry.recordFailure("mock");
        assertThat(registry.shouldSkip("mock")).isFalse(); // janela zerou no fechamento
    }

    @Test
    void killSwitchPorProvedorSemprePula() {
        RoutingProperties props = routing(5, 300);
        RoutingProperties.ProviderCircuit disabled = new RoutingProperties.ProviderCircuit();
        disabled.setEnabled(false);
        props.getCircuitBreaker().getProviders().put("mock", disabled);

        PixProviderHealthRegistry registry = registry(props, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(registry.shouldSkip("mock")).isTrue();
        assertThat(registry.shouldSkip("pagarme")).isFalse();
    }

    @Test
    void circuitoPorLojistaDesviaSoOVolumeDele() {
        PixProviderHealthRegistry registry = registry(routing(5, 300), Clock.fixed(NOW, ZoneOffset.UTC));
        UUID risky = UUID.randomUUID();
        UUID white = UUID.randomUUID();

        registry.openForMerchant(risky, "pagarme");

        assertThat(registry.shouldSkipForMerchant("pagarme", risky)).isTrue();
        assertThat(registry.shouldSkipForMerchant("pagarme", white)).isFalse();
        // O circuito GLOBAL do provedor continua fechado (só o lojista desviou).
        assertThat(registry.shouldSkip("pagarme")).isFalse();
    }

    @Test
    void falhasForaDaJanelaNaoSomam() {
        MutableClock clock = new MutableClock(NOW);
        PixProviderHealthRegistry registry = registry(routing(2, 300), clock);

        registry.recordFailure("mock"); // t=0
        clock.advanceSeconds(120);      // fora da janela de 60s
        registry.recordFailure("mock"); // a falha de t=0 foi podada → só 1

        assertThat(registry.shouldSkip("mock")).isFalse();
        verifyNoInteractions(alertPublisher);
    }
}
