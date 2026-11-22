package com.foxcash.domain.model.delivery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Testes do {@link DeliveryAccessLog} (Spec 49+): criação e restauração expõem
 * todos os campos (round 12 — NO_COVERAGE de getters: id/deliveryId/accessedAt/
 * ip/device/note + restore).
 */
class DeliveryAccessLogTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID DELIVERY_ID = UUID.randomUUID();

    @Test
    void createSetsFieldsAndId() {
        DeliveryAccessLog log = DeliveryAccessLog.create(DELIVERY_ID, NOW,
                "203.0.113.9", "Mozilla/5.0", "acessou o link do servidor");

        // Mutations (NullReturnVals L49/L53/L57 + EmptyObjectReturnVals
        // L61/L65/L69): todos os getters expostos (mutantes trocariam por null/"").
        assertNotNull(log.id());
        assertEquals(DELIVERY_ID, log.deliveryId());
        assertEquals(NOW, log.accessedAt());
        assertEquals("203.0.113.9", log.ip());
        assertEquals("Mozilla/5.0", log.device());
        assertEquals("acessou o link do servidor", log.note());
    }

    @Test
    void createRequiresDeliveryIdAndNow() {
        assertThrows(NullPointerException.class,
                () -> DeliveryAccessLog.create(null, NOW, "ip", "dev", null));
        assertThrows(NullPointerException.class,
                () -> DeliveryAccessLog.create(DELIVERY_ID, null, "ip", "dev", null));
    }

    @Test
    void restoreKeepsAllFields() {
        // Mutation (NullReturnVals L45): restore devolve o log restaurado com
        // todos os campos (mutante → null → NPE em qualquer getter).
        UUID id = UUID.randomUUID();
        Instant accessedAt = NOW.minusSeconds(300);

        DeliveryAccessLog log = DeliveryAccessLog.restore(id, DELIVERY_ID, accessedAt,
                "200.1.2.3", "curl/8", null);

        assertEquals(id, log.id());
        assertEquals(DELIVERY_ID, log.deliveryId());
        assertEquals(accessedAt, log.accessedAt());
        assertEquals("200.1.2.3", log.ip());
        assertEquals("curl/8", log.device());
        assertEquals(null, log.note());
    }
}
