package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link AutoWithdrawProperties} (Spec 70): defaults (kill-switch on,
 * cron 5 min UTC, limiar default R$ 1.000, piso R$ 1,00, teto 100/execucao) e
 * overrides via setters.
 */
class AutoWithdrawPropertiesTest {

    @Test
    void defaultsMatchSpec() {
        AutoWithdrawProperties properties = new AutoWithdrawProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getCron()).isEqualTo("0 */5 * * * *");
        assertThat(properties.getDefaultThreshold()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(properties.getMinThreshold()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(properties.getMaxWithdrawalsPerRun()).isEqualTo(100);
    }

    @Test
    void overridesAreRespected() {
        AutoWithdrawProperties properties = new AutoWithdrawProperties();
        properties.setEnabled(false);
        properties.setCron("*/30 * * * * *");
        properties.setDefaultThreshold(new BigDecimal("500.00"));
        properties.setMinThreshold(new BigDecimal("10.00"));
        properties.setMaxWithdrawalsPerRun(5);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getCron()).isEqualTo("*/30 * * * * *");
        assertThat(properties.getDefaultThreshold()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(properties.getMinThreshold()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(properties.getMaxWithdrawalsPerRun()).isEqualTo(5);
    }
}
