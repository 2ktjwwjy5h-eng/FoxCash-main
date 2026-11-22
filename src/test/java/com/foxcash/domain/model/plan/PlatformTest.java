package com.foxcash.domain.model.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link Platform} (Spec 49): parse case-insensitive com trim;
 * null/blank e valor desconhecido → 400.
 */
class PlatformTest {

    @Test
    void parseRequiresNonBlank() {
        assertThatThrownBy(() -> Platform.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatoria");
        assertThatThrownBy(() -> Platform.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Platform.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseIsCaseInsensitiveAndTrimmed() {
        assertThat(Platform.parse("discord")).isEqualTo(Platform.DISCORD);
        assertThat(Platform.parse("DISCORD")).isEqualTo(Platform.DISCORD);
        assertThat(Platform.parse(" telegram ")).isEqualTo(Platform.TELEGRAM);
        assertThat(Platform.parse("Telegram")).isEqualTo(Platform.TELEGRAM);
    }

    @Test
    void parseInvalidValueThrows() {
        assertThatThrownBy(() -> Platform.parse("whatsapp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform invalida");
    }
}
