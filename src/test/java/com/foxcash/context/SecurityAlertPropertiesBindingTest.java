package com.foxcash.context;

import com.foxcash.infrastructure.config.SecurityAlertProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 3 — binding de properties (Spec 68).
 *
 * <p>Vincula o {@link SecurityAlertProperties} via {@link Binder} (o MESMO
 * mecanismo do {@code @ConfigurationProperties} do boot) contra os valores
 * REAIS do {@code application.yml}: se o yml e o código divergirem (ex.: limiar
 * 10 → 15), o teste falha — sentinela de alinhamento código ↔ configuração. Os
 * defaults esperados vêm do arquivo real via {@link TestYaml} (com placeholders
 * resolvidos), não de constantes duplicadas no teste.
 */
class SecurityAlertPropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static SecurityAlertProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.security.alert", Bindable.of(SecurityAlertProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.security.alert falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        SecurityAlertProperties props = bind(TestYaml.resolvedProperties(YAML));

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getStore()).isEqualTo("memory");
        assertThat(props.getThrottleSeconds()).isEqualTo(300);

        assertThat(props.getLoginFailure().getThreshold()).isEqualTo(10);
        assertThat(props.getLoginFailure().getWindowSeconds()).isEqualTo(60);

        assertThat(props.getRateLimit().getThreshold()).isEqualTo(20);
        assertThat(props.getRateLimit().getWindowSeconds()).isEqualTo(60);

        assertThat(props.getSignatureFailure().getThreshold()).isEqualTo(20);
        assertThat(props.getSignatureFailure().getWindowSeconds()).isEqualTo(60);

        assertThat(props.getBodyInspection().getThreshold()).isEqualTo(5);
        assertThat(props.getBodyInspection().getWindowSeconds()).isEqualTo(60);
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.security.alert.store", "redis");
        props.setProperty("foxcash.security.alert.throttle-seconds", "120");
        props.setProperty("foxcash.security.alert.login-failure.threshold", "25");

        SecurityAlertProperties bound = bind(props);

        assertThat(bound.getStore()).isEqualTo("redis");
        assertThat(bound.getThrottleSeconds()).isEqualTo(120);
        assertThat(bound.getLoginFailure().getThreshold()).isEqualTo(25);
        assertThat(bound.isEnabled()).isTrue(); // não sobrescrito → default real
    }
}
