package com.foxcash.context;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do helper {@link TestYaml} (Spec 68 — task item 2): parse com o mesmo
 * parser do boot, resolução de placeholders {@code ${ENV:default}} (caminho
 * default E caminho env, com lookup injetável) e erro de chave ausente.
 */
class TestYamlTest {

    @Test
    void loadPropertiesParsesRealYamlAndFlattensKeys() {
        Properties props = TestYaml.loadProperties("application.yml");
        assertThat(props).isNotEmpty();
        // chave aninhada vira propriedade pontilhada (mesmo flatten do boot)
        assertThat(props).containsKey("foxcash.security.alert.store");
    }

    @Test
    void resolvedUsesDefaultWhenEnvAbsent() {
        assertThat(TestYaml.resolved("application.yml", "foxcash.security.alert.store",
                name -> null)).isEqualTo("memory");
    }

    @Test
    void resolvedUsesEnvValueWhenPresent() {
        // lookup injetável simula a env var FOXCASH_SECURITY_ALERT_STORE=redis
        Map<String, String> env = Map.of("FOXCASH_SECURITY_ALERT_STORE", "redis");
        assertThat(TestYaml.resolved("application.yml", "foxcash.security.alert.store",
                env::get)).isEqualTo("redis");
    }

    @Test
    void placeholderWithoutDefaultStaysLiteralWhenEnvAbsent() {
        // application.yml tem ${FOXCASH_CRYPTO_SECRET} sem default no base — sem env
        // a resolução preserva o literal (comportamento do boot: fail-fast é
        // decisão do profile, não do resolver).
        assertThat(TestYaml.resolved("application.yml", "foxcash.crypto.secret",
                name -> null)).isEqualTo("${FOXCASH_CRYPTO_SECRET}");
    }

    @Test
    void resolvedPropertiesResolvesAllValues() {
        Properties props = TestYaml.resolvedProperties("application.yml");
        assertThat(props.getProperty("foxcash.security.alert.store")).isEqualTo("memory");
        assertThat(props.getProperty("foxcash.security.alert.throttle-seconds")).isEqualTo("300");
    }

    @Test
    void missingKeyThrows() {
        assertThatThrownBy(() -> TestYaml.resolved("application.yml", "foxcash.nao.existe"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("foxcash.nao.existe");
    }
}
