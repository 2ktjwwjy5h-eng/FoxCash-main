package com.foxcash.context;

import com.foxcash.infrastructure.config.SweepProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.math.BigDecimal;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 3 — binding de properties (Spec 68/L16 + Spec 92 T4.1): vincula o
 * {@link SweepProperties} via {@link Binder} (o MESMO mecanismo do
 * {@code @ConfigurationProperties} do boot) contra os valores REAIS do
 * {@code application.yml} — se o yml e o código divergirem, o teste falha
 * (sentinela de alinhamento código ↔ configuração). O parse do yml completo
 * (TestYaml.loadProperties) também garante que o bloco {@code foxcash.psp.sweep}
 * NÃO colidiu com chaves existentes (L16).
 */
class SweepPropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static SweepProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.psp.sweep", Bindable.of(SweepProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.psp.sweep falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        SweepProperties props = bind(TestYaml.resolvedProperties(YAML));

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getCron()).isEqualTo("0 0 6 * * *");
        assertThat(props.getBufferAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(props.getExposureAlert()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.psp.sweep.enabled", "true");
        props.setProperty("foxcash.psp.sweep.cron", "*/30 * * * * *");
        props.setProperty("foxcash.psp.sweep.buffer-amount", "200.00");
        props.setProperty("foxcash.psp.sweep.destination-key", "evp-corporativo");
        props.setProperty("foxcash.psp.sweep.exposure-alert", "5000");

        SweepProperties bound = bind(props);

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.getCron()).isEqualTo("*/30 * * * * *");
        assertThat(bound.getBufferAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(bound.getDestinationKey()).isEqualTo("evp-corporativo");
        assertThat(bound.getExposureAlert()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    void sweepBlockDoesNotCollideWithExistingKeys() {
        // L16 (auditoria Spec 66): chave duplicada no mesmo nível quebra o boot.
        // O parse do yml completo (TestYaml.loadProperties) lança DuplicateKeyException
        // se houver duplicata — garante que o bloco sweep não colidiu.
        Properties props = TestYaml.resolvedProperties(YAML);
        assertThat(props.getProperty("foxcash.psp.sweep.enabled")).isEqualTo("false");
        assertThat(props.getProperty("foxcash.psp.payout-provider")).isNotNull();
        assertThat(props.getProperty("foxcash.psp.provider")).isNotNull();
    }
}
