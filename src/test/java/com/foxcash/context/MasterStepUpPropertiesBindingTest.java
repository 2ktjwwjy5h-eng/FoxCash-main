package com.foxcash.context;

import com.foxcash.infrastructure.config.MasterStepUpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 3 — binding de properties (Spec 68/69): vincula o
 * {@link MasterStepUpProperties} via {@link Binder} (o MESMO mecanismo do
 * {@code @ConfigurationProperties} do boot) contra os valores REAIS do
 * {@code application.yml} — se o yml e o código divergirem, o teste falha
 * (sentinela de alinhamento código ↔ configuração). Também garante que o bloco
 * {@code foxcash.security.master.step-up} NÃO colide com chaves existentes
 * (L16 — chave duplicada no mesmo nível quebra o boot).
 */
class MasterStepUpPropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static MasterStepUpProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.security.master.step-up", Bindable.of(MasterStepUpProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.security.master.step-up falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        MasterStepUpProperties props = bind(TestYaml.resolvedProperties(YAML));

        assertThat(props.getRequiredPermissions()).containsExactly(
                "withdrawal.administrative",
                "receivable.write_off",
                "invoice.collect",
                "master.profiles.manage",
                // Spec 98: + merchant.debit, merchant.ban no yml real.
                "merchant.debit",
                "merchant.ban");
        assertThat(props.getWindow()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.security.master.step-up.window", "30m");
        props.setProperty("foxcash.security.master.step-up.required-permissions[0]", "invoice.collect");

        MasterStepUpProperties bound = bind(props);

        assertThat(bound.getWindow()).isEqualTo(Duration.ofMinutes(30));
        assertThat(bound.getRequiredPermissions().get(0)).isEqualTo("invoice.collect");
    }

    @Test
    void stepUpBlockDoesNotCollideWithExistingSecurityKeys() {
        // L16 (auditoria Spec 66): chave duplicada no mesmo nível quebra o boot.
        // O parse do yml completo (TestYaml.loadProperties) lança DuplicateKeyException
        // se houver duplicata — garante que o bloco master/step-up não colidiu.
        Properties props = TestYaml.resolvedProperties(YAML);
        assertThat(props.getProperty("foxcash.security.master.step-up.window")).isEqualTo("15m");
        assertThat(props.getProperty("foxcash.security.alert.enabled")).isNotNull();
        assertThat(props.getProperty("foxcash.security.rate-limit.max-requests")).isNotNull();
    }
}
