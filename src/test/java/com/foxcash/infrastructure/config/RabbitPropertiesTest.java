package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link RabbitProperties} (Spec 134): defaults seguros (feature
 * OPT-IN — desligada; filas com prefixo foxcash) e overrides via setter
 * (binding por env {@code FOXCASH_RABBIT_*}).
 */
class RabbitPropertiesTest {

    @Test
    void defaultsAreOptInDisabled() {
        RabbitProperties properties = new RabbitProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getExchange()).isEqualTo("foxcash.outbox");
        assertThat(properties.getQueue()).isEqualTo("foxcash.outbox.queue");
        assertThat(properties.getDeadLetterQueue()).isEqualTo("foxcash.outbox.dlq");
        assertThat(properties.getRoutingKey()).isEqualTo("outbox");
        assertThat(properties.getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void overridesAreRespected() {
        RabbitProperties properties = new RabbitProperties();
        properties.setEnabled(true);
        properties.setExchange("ex.alt");
        properties.setQueue("q.alt");
        properties.setDeadLetterQueue("q.alt.dlq");
        properties.setRoutingKey("rk");
        properties.setMaxAttempts(5);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getExchange()).isEqualTo("ex.alt");
        assertThat(properties.getQueue()).isEqualTo("q.alt");
        assertThat(properties.getDeadLetterQueue()).isEqualTo("q.alt.dlq");
        assertThat(properties.getRoutingKey()).isEqualTo("rk");
        assertThat(properties.getMaxAttempts()).isEqualTo(5);
    }
}
