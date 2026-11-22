package com.foxcash.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do binding do {@link MasterPanelProperties} (Spec 97, D6): defaults
 * documentados (baixo 2%, medio 5%) e setters expostos (binding do yml).
 */
class MasterPanelPropertiesTest {

    @Test
    void defaultsAreTwoAndFivePercent() {
        MasterPanelProperties properties = new MasterPanelProperties();

        assertThat(properties.getRiskThresholds().getLowPercent())
                .isEqualByComparingTo(BigDecimal.valueOf(2.0));
        assertThat(properties.getRiskThresholds().getMidPercent())
                .isEqualByComparingTo(BigDecimal.valueOf(5.0));
    }

    @Test
    void customValuesReflectBinding() {
        MasterPanelProperties properties = new MasterPanelProperties();
        properties.getRiskThresholds().setLowPercent(BigDecimal.valueOf(1.5));
        properties.getRiskThresholds().setMidPercent(BigDecimal.valueOf(4.0));

        assertThat(properties.getRiskThresholds().getLowPercent())
                .isEqualByComparingTo("1.5");
        assertThat(properties.getRiskThresholds().getMidPercent())
                .isEqualByComparingTo("4.0");
    }
}
