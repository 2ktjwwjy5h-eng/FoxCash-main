package com.foxcash.context;

import com.foxcash.infrastructure.config.AutoWithdrawProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.math.BigDecimal;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 3 — binding de properties (Spec 68/70): vincula o
 * {@link AutoWithdrawProperties} via {@link Binder} (o MESMO mecanismo do
 * {@code @ConfigurationProperties} do boot) contra os valores REAIS do
 * {@code application.yml} — se o yml e o código divergirem, o teste falha
 * (sentinela de alinhamento código ↔ configuração). O parse do yml completo
 * (TestYaml.loadProperties) também garante que o bloco
 * {@code foxcash.auto-withdraw} NÃO colidiu com chaves existentes (L16).
 */
class AutoWithdrawPropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static AutoWithdrawProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.auto-withdraw", Bindable.of(AutoWithdrawProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.auto-withdraw falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        AutoWithdrawProperties props = bind(TestYaml.resolvedProperties(YAML));

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getCron()).isEqualTo("0 */5 * * * *");
        assertThat(props.getDefaultThreshold()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(props.getMinThreshold()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(props.getMaxWithdrawalsPerRun()).isEqualTo(100);
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.auto-withdraw.enabled", "false");
        props.setProperty("foxcash.auto-withdraw.cron", "*/30 * * * * *");
        props.setProperty("foxcash.auto-withdraw.default-threshold", "500.00");
        props.setProperty("foxcash.auto-withdraw.max-withdrawals-per-run", "5");

        AutoWithdrawProperties bound = bind(props);

        assertThat(bound.isEnabled()).isFalse();
        assertThat(bound.getCron()).isEqualTo("*/30 * * * * *");
        assertThat(bound.getDefaultThreshold()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(bound.getMaxWithdrawalsPerRun()).isEqualTo(5);
    }

    @Test
    void autoWithdrawBlockDoesNotCollideWithExistingKeys() {
        // L16 (auditoria Spec 66): chave duplicada no mesmo nível quebra o boot.
        // O parse do yml completo (TestYaml.loadProperties) lança DuplicateKeyException
        // se houver duplicata — garante que o bloco auto-withdraw não colidiu.
        Properties props = TestYaml.resolvedProperties(YAML);
        assertThat(props.getProperty("foxcash.auto-withdraw.enabled")).isEqualTo("true");
        assertThat(props.getProperty("foxcash.billing.block-withdrawals-on-debt")).isNotNull();
        assertThat(props.getProperty("foxcash.psp.provider")).isNotNull();
    }
}
