package com.foxcash.domain.model.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link ScheduledMessageAudience} (Spec 51): default BUYERS para
 * null/blank; parse case-insensitive com trim; inválido → 400.
 */
class ScheduledMessageAudienceTest {

    @Test
    void parseNullOrBlankDefaultsToBuyers() {
        assertThat(ScheduledMessageAudience.parse(null)).isEqualTo(ScheduledMessageAudience.BUYERS);
        assertThat(ScheduledMessageAudience.parse("")).isEqualTo(ScheduledMessageAudience.BUYERS);
        assertThat(ScheduledMessageAudience.parse("   ")).isEqualTo(ScheduledMessageAudience.BUYERS);
    }

    @Test
    void parseIsCaseInsensitiveAndTrimmed() {
        assertThat(ScheduledMessageAudience.parse("buyers")).isEqualTo(ScheduledMessageAudience.BUYERS);
        assertThat(ScheduledMessageAudience.parse(" BUYERS ")).isEqualTo(ScheduledMessageAudience.BUYERS);
        assertThat(ScheduledMessageAudience.parse("Buyers")).isEqualTo(ScheduledMessageAudience.BUYERS);
    }

    @Test
    void parseInvalidValueThrows() {
        // Nesta versão apenas BUYERS — "all" (evolução planejada) ainda é inválido.
        assertThatThrownBy(() -> ScheduledMessageAudience.parse("all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BUYERS");
    }
}
