package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link ConsoleProperties} (Spec 71): default do
 * {@code serve-frontend} = {@code false} (API pura — nenhuma rota extra exposta)
 * e override via setter (binding por env {@code FOXCASH_CONSOLE_SERVE_FRONTEND}).
 */
class ConsolePropertiesTest {

    @Test
    void serveFrontendDefaultsToFalse() {
        ConsoleProperties properties = new ConsoleProperties();

        assertThat(properties.isServeFrontend()).isFalse();
    }

    @Test
    void overrideIsRespected() {
        ConsoleProperties properties = new ConsoleProperties();
        properties.setServeFrontend(true);

        assertThat(properties.isServeFrontend()).isTrue();
    }
}
