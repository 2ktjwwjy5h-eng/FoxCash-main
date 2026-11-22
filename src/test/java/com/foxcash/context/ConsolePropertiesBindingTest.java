package com.foxcash.context;

import com.foxcash.infrastructure.config.ConsoleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 3 — binding de properties (Spec 68/71): vincula o {@link ConsoleProperties}
 * via {@link Binder} (o MESMO mecanismo do {@code @ConfigurationProperties} do
 * boot) contra os valores REAIS do {@code application.yml} — se o yml e o
 * código divergirem, o teste falha (sentinela de alinhamento código ↔
 * configuração). O parse do yml completo (TestYaml.loadProperties) também
 * garante que o bloco {@code foxcash.console} NÃO colidiu com chaves existentes
 * (L16).
 */
class ConsolePropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static ConsoleProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.console", Bindable.of(ConsoleProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.console falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        ConsoleProperties props = bind(TestYaml.resolvedProperties(YAML));

        // Default false: API pura — sem o flag nenhuma rota extra é exposta.
        assertThat(props.isServeFrontend()).isFalse();
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.console.serve-frontend", "true");

        ConsoleProperties bound = bind(props);

        assertThat(bound.isServeFrontend()).isTrue();
    }

    @Test
    void consoleBlockDoesNotCollideWithExistingKeys() {
        // L16 (auditoria Spec 66): chave duplicada no mesmo nível quebra o boot.
        // O parse do yml completo (TestYaml.loadProperties) lança DuplicateKeyException
        // se houver duplicata — garante que o bloco console não colidiu com o portal.
        Properties props = TestYaml.resolvedProperties(YAML);
        assertThat(props.getProperty("foxcash.console.serve-frontend")).isEqualTo("false");
        assertThat(props.getProperty("foxcash.portal.serve-frontend")).isNotNull();
        assertThat(props.getProperty("foxcash.crypto.secret")).isNotNull();
    }
}
