package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link BroadcastProperties} (Spec 79/D11): defaults seguros
 * (repost ativo, cron a cada 5min) e overrides via setter (binding por env
 * {@code FOXCASH_BROADCAST_*}).
 */
class BroadcastPropertiesTest {

    @Test
    void defaultsAreSafe() {
        BroadcastProperties properties = new BroadcastProperties();

        assertThat(properties.isRepostEnabled()).isTrue();
        assertThat(properties.getRepostCron()).isEqualTo("0 */5 * * * *");
    }

    @Test
    void overridesAreRespected() {
        BroadcastProperties properties = new BroadcastProperties();
        properties.setRepostEnabled(false);
        properties.setRepostCron("0 */15 * * * *");

        assertThat(properties.isRepostEnabled()).isFalse();
        assertThat(properties.getRepostCron()).isEqualTo("0 */15 * * * *");
    }
}
