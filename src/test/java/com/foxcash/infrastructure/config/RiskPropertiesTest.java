package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link RiskProperties} (Spec 128 — T2): defaults conservadores do
 * bloco {@code auto} (Risk Scoring Engine) e do auto-suspend (Spec 19, default
 * {@code false} por decisão do usuário) + setters expostos (binding do yml).
 */
class RiskPropertiesTest {

    @Test
    void autoSuspendDefaultIsDisabled() {
        // Decisão do usuário 2026-08-16: sem bloqueio de conta automático.
        assertThat(new RiskProperties().isAutoSuspendEnabled()).isFalse();
    }

    @Test
    void autoDefaultsAreConservative() {
        RiskProperties.RiskAutoProperties auto = new RiskProperties().getAuto();

        assertThat(auto.isEnabled()).isTrue();
        assertThat(auto.getMedThresholdPercent()).isEqualByComparingTo(BigDecimal.valueOf(10.0));
        assertThat(auto.getMedRecoverPercent()).isEqualByComparingTo(BigDecimal.valueOf(5.0));
        assertThat(auto.getRateLimitReductionPercent()).isEqualByComparingTo(BigDecimal.valueOf(50.0));
        assertThat(auto.getDefaultRps()).isEqualTo(5);
        assertThat(auto.getDefaultRpm()).isEqualTo(300);
        assertThat(auto.getFloorRps()).isEqualTo(1);
        assertThat(auto.getFloorRpm()).isEqualTo(60);
    }

    @Test
    void customValuesReflectBinding() {
        RiskProperties properties = new RiskProperties();
        properties.getAuto().setMedThresholdPercent(BigDecimal.valueOf(15.0));
        properties.getAuto().setMedRecoverPercent(BigDecimal.valueOf(7.5));
        properties.getAuto().setRateLimitReductionPercent(BigDecimal.valueOf(25.0));
        properties.getAuto().setDefaultRps(8);
        properties.getAuto().setFloorRpm(120);
        properties.setAutoSuspendEnabled(true);

        assertThat(properties.getAuto().getMedThresholdPercent()).isEqualByComparingTo("15.0");
        assertThat(properties.getAuto().getMedRecoverPercent()).isEqualByComparingTo("7.5");
        assertThat(properties.getAuto().getRateLimitReductionPercent()).isEqualByComparingTo("25.0");
        assertThat(properties.getAuto().getDefaultRps()).isEqualTo(8);
        assertThat(properties.getAuto().getFloorRpm()).isEqualTo(120);
        assertThat(properties.isAutoSuspendEnabled()).isTrue();
    }
}
