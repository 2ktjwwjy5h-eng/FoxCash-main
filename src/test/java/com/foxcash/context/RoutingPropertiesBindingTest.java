package com.foxcash.context;

import com.foxcash.infrastructure.config.RoutingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 3 — binding de properties (Spec 68/L16 + Spec 91 T5.1): vincula o
 * {@link RoutingProperties} via {@link Binder} (o MESMO mecanismo do
 * {@code @ConfigurationProperties} do boot) contra os valores REAIS do
 * {@code application.yml} — se o yml e o código divergirem, o teste falha
 * (sentinela de alinhamento código ↔ configuração).
 */
class RoutingPropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static RoutingProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.psp.routing", Bindable.of(RoutingProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.psp.routing falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        RoutingProperties props = bind(TestYaml.resolvedProperties(YAML));

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getProviders()).isEmpty();
        // Policies default + high-risk (score-min 60) existem no yml.
        assertThat(props.getPolicies()).containsKeys("default", "high-risk");
        assertThat(props.getPolicies().get("high-risk").getScoreMin()).isEqualTo(60);
        // Circuit breaker: defaults.
        assertThat(props.getCircuitBreaker().getWindowSeconds()).isEqualTo(60);
        assertThat(props.getCircuitBreaker().getFailureThreshold()).isEqualTo(5);
        assertThat(props.getCircuitBreaker().getCooldownSeconds()).isEqualTo(300);
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.psp.routing.enabled", "true");
        props.setProperty("foxcash.psp.routing.providers", "pagarme,asaas");
        props.setProperty("foxcash.psp.routing.circuit-breaker.failure-threshold", "3");

        RoutingProperties bound = bind(props);

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.getProviders()).isEqualTo("pagarme,asaas");
        assertThat(bound.getCircuitBreaker().getFailureThreshold()).isEqualTo(3);
    }

    @Test
    void routingBlockDoesNotCollideWithExistingKeys() {
        // L16: chave duplicada no mesmo nível quebra o boot — o parse do yml
        // completo (TestYaml.resolvedProperties) garante que o bloco routing não
        // colidiu com provider/payout-provider existentes.
        Properties props = TestYaml.resolvedProperties(YAML);
        assertThat(props.getProperty("foxcash.psp.routing.enabled")).isEqualTo("false");
        assertThat(props.getProperty("foxcash.psp.provider")).isNotNull();
        assertThat(props.getProperty("foxcash.psp.payout-provider")).isNotNull();
    }
}
