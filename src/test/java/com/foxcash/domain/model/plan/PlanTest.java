package com.foxcash.domain.model.plan;

import com.foxcash.domain.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do modelo de dominio do plano (Spec 49): validacoes (nome/preco/desc/
 * imagem/cor), update parcial, activate/deactivate.
 */
class PlanTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    void createSetsFieldsAndActive() {
        Plan plan = Plan.create(STORE_ID, "VIP Bronze", Money.of("39.90"),
                "Fotos + videos", "https://cdn/x.png", "#8E44AD", true, NOW);

        assertEquals(STORE_ID, plan.storeId());
        assertEquals("VIP Bronze", plan.name());
        assertEquals("39.90", plan.price().amount().toString());
        assertTrue(plan.featured());
        assertTrue(plan.active());
        assertEquals(NOW, plan.createdAt());
        assertEquals(NOW, plan.updatedAt());
    }

    @Test
    void createRequiresNameAndPositivePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> Plan.create(STORE_ID, null, Money.of("10"), null, null, null, false, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Plan.create(STORE_ID, "   ", Money.of("10"), null, null, null, false, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Plan.create(STORE_ID, "Plano", null, null, null, null, false, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Plan.create(STORE_ID, "Plano", Money.of("0"), null, null, null, false, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Plan.create(STORE_ID, "Plano", Money.of("-5"), null, null, null, false, NOW));
    }

    @Test
    void createRejectsLongNameAndInvalidEmbedColor() {
        assertThrows(IllegalArgumentException.class,
                () -> Plan.create(STORE_ID, "a".repeat(121), Money.of("10"), null, null, null, false, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Plan.create(STORE_ID, "Plano", Money.of("10"), null, null, "red", false, NOW));
    }

    @Test
    void updateKeepsNullFieldsAndValidatesMerged() {
        Plan plan = Plan.create(STORE_ID, "VIP Bronze", Money.of("39.90"),
                "Desc", null, "#8E44AD", false, NOW);

        plan.update("VIP Ouro", Money.of("79.90"), null, null, null, true, NOW.plusSeconds(60));

        assertEquals("VIP Ouro", plan.name());
        assertEquals("79.90", plan.price().amount().toString());
        assertEquals("Desc", plan.description());
        assertEquals("#8E44AD", plan.embedColor());
        assertTrue(plan.featured());
        assertEquals(NOW.plusSeconds(60), plan.updatedAt());

        assertThrows(IllegalArgumentException.class,
                () -> plan.update(null, Money.of("0"), null, null, null, null, NOW.plusSeconds(120)));
    }

    @Test
    void activateAndDeactivateToggle() {
        Plan plan = Plan.create(STORE_ID, "Plano", Money.of("10"), null, null, null, false, NOW);
        plan.deactivate();
        assertFalse(plan.active());
        plan.activate();
        assertTrue(plan.active());
    }

    @Test
    void createAcceptsExactBoundaryLengths() {
        // Mutation (ConditionalsBoundary > <-> >=): o limite exato é aceito.
        Plan plan = Plan.create(STORE_ID, "a".repeat(120), Money.of("10"),
                "d".repeat(500), "i".repeat(500), "#8E44AD", false, NOW);

        assertEquals(120, plan.name().length());
        assertEquals(500, plan.description().length());
        assertEquals(500, plan.imageUrl().length());
    }

    @Test
    void updateStoresImageUrlAndEmbedColor() {
        // Mutation (NegateConditionals nos ternarios): campos fornecidos gravados.
        Plan plan = Plan.create(STORE_ID, "Plano", Money.of("10"), null, null, "#8E44AD", false, NOW);

        plan.update(null, null, "nova desc", "https://img.png", "#112233", null, NOW.plusSeconds(60));

        assertEquals("nova desc", plan.description());
        assertEquals("https://img.png", plan.imageUrl());
        assertEquals("#112233", plan.embedColor());
    }

    @Test
    void activateAndDeactivateTouchUpdatedAt() {
        // Mutation (VoidMethodCall em touch()): activate/deactivate bumpam updatedAt.
        Plan plan = Plan.restore(UUID.randomUUID(), STORE_ID, "Plano", null, Money.of("10"),
                null, "#8E44AD", false, true, NOW, NOW);

        plan.activate();
        assertTrue(plan.updatedAt().isAfter(NOW));

        plan.deactivate();
        assertTrue(plan.updatedAt().isAfter(NOW));
    }

    @Test
    void deactivateAloneTouchesUpdatedAt() {
        // Mutation (VoidMethodCall L90 — touch removido SÓ de deactivate): o teste
        // acima não isola (activate já bumpa). Restaurar com updatedAt=NOW e chamar
        // APENAS deactivate: o mutante deixaria updatedAt == NOW.
        Plan plan = Plan.restore(UUID.randomUUID(), STORE_ID, "Plano", null, Money.of("10"),
                null, "#8E44AD", false, true, NOW, NOW);

        plan.deactivate();

        assertTrue(plan.updatedAt().isAfter(NOW));
    }

    @Test
    void featuredReflectsCreateFlag() {
        // Mutation (BooleanTrueReturnVals L148): featured() reflete o flag — um
        // plano criado com featured=false NÃO é destaque (o mutante → true).
        Plan featured = Plan.create(STORE_ID, "Destaque", Money.of("10"), null, null,
                "#8E44AD", true, NOW);
        Plan normal = Plan.create(STORE_ID, "Comum", Money.of("10"), null, null,
                "#8E44AD", false, NOW);

        assertTrue(featured.featured());
        assertFalse(normal.featured());
    }

    @Test
    void exposesId() {
        // Mutation (NullReturnVals L120): id() devolve o UUID do plano (mutante → null).
        Plan plan = Plan.create(STORE_ID, "Plano", Money.of("10"), null, null, null, false, NOW);

        assertTrue(plan.id() != null);
    }
}
