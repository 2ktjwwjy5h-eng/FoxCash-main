package com.foxcash.context;

import com.foxcash.infrastructure.config.RiskProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.math.BigDecimal;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 3 — binding de properties (Spec 68/128): vincula o {@link RiskProperties}
 * via {@link Binder} (o MESMO mecanismo do {@code @ConfigurationProperties} do
 * boot) contra os valores REAIS do {@code application.yml} — se o yml e o
 * código divergirem, o teste falha (sentinela de alinhamento código ↔
 * configuração). O parse do yml completo (TestYaml.loadProperties) também
 * garante que o bloco {@code foxcash.risk} (com o novo {@code auto}) NÃO colidiu
 * com chaves existentes (L16).
 */
class RiskPropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static RiskProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.risk", Bindable.of(RiskProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.risk falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        RiskProperties props = bind(TestYaml.resolvedProperties(YAML));

        // Spec 128: auto-suspend default FALSE (decisão do usuário) e o bloco
        // auto com defaults conservadores.
        assertThat(props.isAutoSuspendEnabled()).isFalse();
        assertThat(props.getAuto().isEnabled()).isTrue();
        assertThat(props.getAuto().getMedThresholdPercent())
                .isEqualByComparingTo(BigDecimal.valueOf(10.0));
        assertThat(props.getAuto().getMedRecoverPercent())
                .isEqualByComparingTo(BigDecimal.valueOf(5.0));
        assertThat(props.getAuto().getRateLimitReductionPercent())
                .isEqualByComparingTo(BigDecimal.valueOf(50.0));
        assertThat(props.getAuto().getDefaultRps()).isEqualTo(5);
        assertThat(props.getAuto().getDefaultRpm()).isEqualTo(300);
        assertThat(props.getAuto().getFloorRps()).isEqualTo(1);
        assertThat(props.getAuto().getFloorRpm()).isEqualTo(60);
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.risk.auto.med-threshold-percent", "15.0");
        props.setProperty("foxcash.risk.auto-suspend-enabled", "true");

        RiskProperties bound = bind(props);

        assertThat(bound.getAuto().getMedThresholdPercent())
                .isEqualByComparingTo("15.0");
        assertThat(bound.isAutoSuspendEnabled()).isTrue();
    }

    @Test
    void riskBlockDoesNotCollideWithExistingKeys() {
        // L16 (auditoria Spec 66): chave duplicada no mesmo nível quebra o boot.
        // O parse do yml completo lança DuplicateKeyException se houver duplicata.
        Properties props = TestYaml.resolvedProperties(YAML);
        assertThat(props.getProperty("foxcash.risk.auto.enabled")).isEqualTo("true");
        assertThat(props.getProperty("foxcash.risk.auto-suspend-enabled")).isEqualTo("false");
        assertThat(props.getProperty("foxcash.risk.chargeback-alert-percent")).isEqualTo("1.0");
        // Bloco vizinho preservado (scheduler continua no mesmo nível).
        assertThat(props.getProperty("foxcash.scheduler.invoice-cron")).isNotNull();
    }
}
