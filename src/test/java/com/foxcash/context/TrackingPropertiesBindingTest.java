package com.foxcash.context;

import com.foxcash.infrastructure.config.TrackingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Camada 3 — binding de properties (Spec 68/81): vincula o {@link TrackingProperties}
 * contra os valores REAIS do {@code application.yml} (sentinela de alinhamento
 * código ↔ configuração; o parse do yml completo também pega chave duplicada/L16).
 */
class TrackingPropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static TrackingProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.tracking", Bindable.of(TrackingProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.tracking falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        TrackingProperties props = bind(TestYaml.resolvedProperties(YAML));

        assertThat(props.isDispatchEnabled()).isTrue();
        assertThat(props.getCapiApiVersion()).isEqualTo("v19.0");
        assertThat(props.getCapiBaseUrl()).isEqualTo("https://graph.facebook.com");
        assertThat(props.getTiktokBaseUrl()).isEqualTo("https://business-api.tiktok.com/open_api/v1.3");
        assertThat(props.getUtmifyBaseUrl()).isEqualTo("https://api.utmify.com.br");
        assertThat(props.getOtimizeyBaseUrl()).isEqualTo("https://api.otimizey.com.br");
        assertThat(props.getEventLogResponseMax()).isEqualTo(500);
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.tracking.dispatch-enabled", "false");
        props.setProperty("foxcash.tracking.capi-base-url", "http://localhost:9000");

        TrackingProperties bound = bind(props);

        assertThat(bound.isDispatchEnabled()).isFalse();
        assertThat(bound.getCapiBaseUrl()).isEqualTo("http://localhost:9000");
    }

    @Test
    void trackingBlockDoesNotCollideWithExistingKeys() {
        // L16: chave duplicada no mesmo nível quebra o boot — o parse do yml
        // completo (TestYaml.loadProperties) lança DuplicateKeyException.
        Properties props = TestYaml.resolvedProperties(YAML);
        assertThat(props.getProperty("foxcash.tracking.dispatch-enabled")).isEqualTo("true");
        assertThat(props.getProperty("foxcash.broadcast.repost-enabled")).isNotNull();
        assertThat(props.getProperty("foxcash.psp.provider")).isNotNull();
    }

    @Test
    void validateAcceptsRealDefaults() {
        // Spec 81/D6 (SSRF-safe): as base URLs reais passam no fail-fast.
        bind(TestYaml.resolvedProperties(YAML)).validate();
    }

    @Test
    void validateRejectsNonHttpSchemeAndCredentials() {
        TrackingProperties badScheme = bind(TestYaml.resolvedProperties(YAML));
        badScheme.setCapiBaseUrl("ftp://graph.facebook.com");
        assertThatThrownBy(badScheme::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http(s)");

        TrackingProperties credentials = bind(TestYaml.resolvedProperties(YAML));
        credentials.setOtimizeyBaseUrl("https://user:pass@api.otimizey.com.br");
        assertThatThrownBy(credentials::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credenciais");
    }
}
