package com.foxcash.domain.model.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link StoreMessageEvent} (Spec 51): parse case-insensitive com
 * trim para todos os eventos; null/blank e valor desconhecido → 400.
 */
class StoreMessageEventTest {

    @Test
    void parseRequiresNonBlank() {
        assertThatThrownBy(() -> StoreMessageEvent.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatorio");
        assertThatThrownBy(() -> StoreMessageEvent.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreMessageEvent.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseAllEventsCaseInsensitiveAndTrimmed() {
        assertThat(StoreMessageEvent.parse("welcome")).isEqualTo(StoreMessageEvent.WELCOME);
        assertThat(StoreMessageEvent.parse(" AWAITING_PAYMENT ")).isEqualTo(StoreMessageEvent.AWAITING_PAYMENT);
        assertThat(StoreMessageEvent.parse("payment_received")).isEqualTo(StoreMessageEvent.PAYMENT_RECEIVED);
        assertThat(StoreMessageEvent.parse("Delivered")).isEqualTo(StoreMessageEvent.DELIVERED);
        assertThat(StoreMessageEvent.parse("promo")).isEqualTo(StoreMessageEvent.PROMO);
        assertThat(StoreMessageEvent.parse("faq")).isEqualTo(StoreMessageEvent.FAQ);
    }

    @Test
    void parseInvalidValueThrows() {
        assertThatThrownBy(() -> StoreMessageEvent.parse("ORDER_SHIPPED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event invalido");
    }
}
