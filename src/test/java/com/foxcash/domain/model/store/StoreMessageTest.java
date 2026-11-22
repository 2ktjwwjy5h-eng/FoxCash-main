package com.foxcash.domain.model.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes da mensagem automática da loja (Spec 51): criação (validações de
 * event/text 1–1000), restore, update (campos nulos mantêm; event imutável) e
 * setActive.
 */
class StoreMessageTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID STORE_ID = UUID.randomUUID();

    @Test
    void createValidatesEventAndText() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME,
                "Olá 👋 Bem-vindo.", NOW);

        assertThat(message.event()).isEqualTo(StoreMessageEvent.WELCOME);
        assertThat(message.text()).isEqualTo("Olá 👋 Bem-vindo.");
        assertThat(message.active()).isTrue();
        assertThat(message.id()).isNotNull();
        assertThat(message.createdAt()).isEqualTo(NOW);
        assertThat(message.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void createRejectsBlankText() {
        assertThatThrownBy(() -> StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME, "  ", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text e obrigatorio");
    }

    @Test
    void createRejectsTextOver1000() {
        String longText = "x".repeat(1001);

        assertThatThrownBy(() -> StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME, longText, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no maximo 1000");
    }

    @Test
    void createAcceptsExactly1000Chars() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME,
                "x".repeat(1000), NOW);

        assertThat(message.text()).hasSize(1000);
    }

    @Test
    void createRejectsNullEvent() {
        assertThatThrownBy(() -> StoreMessage.create(STORE_ID, null, "Olá", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event e obrigatorio");
    }

    @Test
    void updateKeepsCurrentForNullFieldsAndBumpsUpdatedAt() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.FAQ,
                "Dúvidas? Fale conosco.", NOW);
        Instant later = NOW.plusSeconds(60);

        message.update(null, false, later);

        assertThat(message.text()).isEqualTo("Dúvidas? Fale conosco.");
        assertThat(message.active()).isFalse();
        assertThat(message.updatedAt()).isEqualTo(later);
        assertThat(message.event()).isEqualTo(StoreMessageEvent.FAQ);
    }

    @Test
    void updateChangesTextAndValidates() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME, "Oi.", NOW);

        message.update("Bem-vindo à loja!", null, NOW.plusSeconds(60));

        assertThat(message.text()).isEqualTo("Bem-vindo à loja!");
        assertThat(message.active()).isTrue();

        assertThatThrownBy(() -> message.update("   ", null, NOW.plusSeconds(120)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text e obrigatorio");
    }

    @Test
    void restoreLoadsPersistedState() {
        UUID id = UUID.randomUUID();
        Instant createdAt = NOW.minusSeconds(3600);
        Instant updatedAt = NOW.minusSeconds(60);

        StoreMessage message = StoreMessage.restore(id, STORE_ID, StoreMessageEvent.PAYMENT_RECEIVED,
                "Pagamento confirmado!", false, createdAt, updatedAt);

        assertThat(message.id()).isEqualTo(id);
        assertThat(message.storeId()).isEqualTo(STORE_ID);
        assertThat(message.event()).isEqualTo(StoreMessageEvent.PAYMENT_RECEIVED);
        assertThat(message.active()).isFalse();
        assertThat(message.createdAt()).isEqualTo(createdAt);
        assertThat(message.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void setActiveToggles() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.PROMO, "Promo!", NOW);

        message.setActive(false);

        assertThat(message.active()).isFalse();
    }
}
