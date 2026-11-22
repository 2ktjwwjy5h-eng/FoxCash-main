package com.foxcash.domain.model.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do modelo de dominio do botão do menu (Spec 50): criacao com
 * validacoes (label, action, position), restore, update parcial,
 * activate/deactivate e moveTo.
 */
class StoreButtonTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Test
    void createSetsFieldsAndActive() {
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY,
                null, 0, NOW);

        assertEquals(STORE_ID, button.storeId());
        assertEquals("Comprar", button.label());
        assertEquals(StoreButtonAction.BUY, button.action());
        assertEquals(0, button.position());
        assertTrue(button.active());
        assertEquals(NOW, button.createdAt());
    }

    @Test
    void createRequiresLabelAndAction() {
        assertThrows(IllegalArgumentException.class,
                () -> StoreButton.create(STORE_ID, null, StoreButtonAction.BUY, null, 0, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> StoreButton.create(STORE_ID, "   ", StoreButtonAction.BUY, null, 0, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> StoreButton.create(STORE_ID, "Comprar", null, null, 0, NOW));
    }

    @Test
    void createRejectsLongLabelAndNegativePosition() {
        assertThrows(IllegalArgumentException.class,
                () -> StoreButton.create(STORE_ID, "a".repeat(81), StoreButtonAction.BUY, null, 0, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, -1, NOW));
    }

    @Test
    void updateChangesOnlyProvidedFields() {
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY,
                null, 0, NOW);

        button.update("Instagram", StoreButtonAction.SOCIAL, "{\"url\":\"https://ig.com/x\"}", null);

        assertEquals("Instagram", button.label());
        assertEquals(StoreButtonAction.SOCIAL, button.action());
        assertEquals("{\"url\":\"https://ig.com/x\"}", button.payload());
        assertTrue(button.active());
    }

    @Test
    void updateKeepsNullFields() {
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY,
                null, 0, NOW);

        button.update("Comprar Agora", null, null, null);

        assertEquals("Comprar Agora", button.label());
        assertEquals(StoreButtonAction.BUY, button.action());
        assertTrue(button.active());
    }

    @Test
    void updateRejectsBlankLabel() {
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY,
                null, 0, NOW);

        assertThrows(IllegalArgumentException.class,
                () -> button.update("   ", null, null, null));
    }

    @Test
    void setActiveToggles() {
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY,
                null, 0, NOW);
        button.setActive(false);
        assertFalse(button.active());
        button.setActive(true);
        assertTrue(button.active());
    }

    @Test
    void moveToRepositions() {
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY,
                null, 0, NOW);
        button.moveTo(3);
        assertEquals(3, button.position());
        assertThrows(IllegalArgumentException.class, () -> button.moveTo(-1));
    }

    @Test
    void moveToAcceptsZeroPosition() {
        // Mutation (ConditionalsBoundary L82: position < 0 → <= 0): a posição ZERO
        // é válida (primeiro item do menu). O mutante rejeitaria moveTo(0).
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY,
                null, 0, NOW);

        button.moveTo(0);

        assertEquals(0, button.position());
    }

    @Test
    void restoreKeepsAllFields() {
        UUID id = UUID.randomUUID();
        StoreButton button = StoreButton.restore(id, STORE_ID, "FAQ", StoreButtonAction.FAQ,
                "{\"text\":\"Duvidas?\"}", 2, false, NOW);

        assertEquals(id, button.id());
        assertEquals(STORE_ID, button.storeId());
        assertEquals("FAQ", button.label());
        assertEquals(StoreButtonAction.FAQ, button.action());
        assertEquals("{\"text\":\"Duvidas?\"}", button.payload());
        assertEquals(2, button.position());
        assertFalse(button.active());
        assertEquals(NOW, button.createdAt());
    }

    @Test
    void createAcceptsMaxLabelLength() {
        // Mutation (ConditionalsBoundary > <-> >=): label de 80 chars é aceito.
        StoreButton button = StoreButton.create(STORE_ID, "a".repeat(80), StoreButtonAction.BUY,
                null, 0, NOW);

        assertEquals(80, button.label().length());
    }
}
