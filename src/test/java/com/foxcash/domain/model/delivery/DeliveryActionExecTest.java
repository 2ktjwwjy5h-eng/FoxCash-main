package com.foxcash.domain.model.delivery;

import com.foxcash.domain.model.plan.DeliveryActionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Testes do {@link DeliveryActionExec} (Spec 49): criação/restauração expõem
 * todos os campos (round 12 — NO_COVERAGE de getters: id/deliveryId/actionType/
 * payloadSnapshot + PrimitiveReturns de seq).
 */
class DeliveryActionExecTest {

    private static final UUID DELIVERY_ID = UUID.randomUUID();

    @Test
    void createSetsFieldsAndSeq() {
        // Mutation (PrimitiveReturnsMutator L98): seq() devolve o valor real
        // (o mutante trocaria por 0).
        DeliveryActionExec exec = DeliveryActionExec.create(DELIVERY_ID, 5,
                DeliveryActionType.SEND_LINK, "{\"url\":\"https://t.me/+abc\"}");

        // Mutations (NullReturnVals L90/L94/L102 + EmptyObjectReturnVals L106):
        // todos os getters expostos (mutantes trocariam por null/"").
        assertEquals(DELIVERY_ID, exec.deliveryId());
        assertEquals(5, exec.seq());
        assertEquals(DeliveryActionType.SEND_LINK, exec.actionType());
        assertEquals("{\"url\":\"https://t.me/+abc\"}", exec.payloadSnapshot());
    }

    @Test
    void createRejectsNegativeSeqAndBlankPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryActionExec.create(DELIVERY_ID, -1, DeliveryActionType.SEND_LINK, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryActionExec.create(DELIVERY_ID, 0, DeliveryActionType.SEND_LINK, "  "));
    }

    @Test
    void restoreKeepsAllFields() {
        UUID id = UUID.randomUUID();
        Instant executedAt = Instant.parse("2026-08-06T12:00:00Z");

        DeliveryActionExec exec = DeliveryActionExec.restore(id, DELIVERY_ID, 3,
                DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}",
                DeliveryActionStatus.DONE, "ok", executedAt);

        assertEquals(id, exec.id());
        assertEquals(DELIVERY_ID, exec.deliveryId());
        assertEquals(3, exec.seq());
        assertEquals(DeliveryActionType.SEND_MESSAGE, exec.actionType());
        assertEquals("{\"text\":\"oi\"}", exec.payloadSnapshot());
        assertEquals(DeliveryActionStatus.DONE, exec.status());
        assertEquals("ok", exec.note());
        assertEquals(executedAt, exec.executedAt());
    }
}
