package com.foxcash.domain.model.delivery;

import com.foxcash.domain.model.plan.DeliveryActionType;
import com.foxcash.domain.model.plan.Platform;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do modelo de entrega (Spec 49): criacao com destinatario, gate por
 * acao (só PENDING → DONE|FAILED), avaliacao automatica (todas DONE →
 * DELIVERED; qualquer FAILED → FAILED), retry (reabre FAILED → PENDING) e
 * plano sem acoes (DELIVERED direto).
 */
class PlanDeliveryTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    private PlanDelivery delivery() {
        return PlanDelivery.create(STORE_ID, PLAN_ID, TX_ID, Platform.DISCORD,
                "123456789012345678", "hash-buyer", NOW);
    }

    private DeliveryActionExec exec(int seq, DeliveryActionStatus status) {
        return DeliveryActionExec.restore(UUID.randomUUID(), UUID.randomUUID(), seq,
                DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}", status, null, null);
    }

    @Test
    void createRequiresPlatformAndPlatformUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> PlanDelivery.create(STORE_ID, PLAN_ID, TX_ID, null, "u1", null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> PlanDelivery.create(STORE_ID, PLAN_ID, TX_ID, Platform.DISCORD, " ", null, NOW));
    }

    @Test
    void markDeliveredOnPlanWithoutActions() {
        PlanDelivery delivery = delivery();
        assertTrue(delivery.markDelivered(NOW));
        assertEquals(PlanDeliveryStatus.DELIVERED, delivery.status());
        assertEquals(NOW, delivery.deliveredAt());
        // No-op em entrega já entregue.
        assertFalse(delivery.markDelivered(NOW.plusSeconds(1)));
    }

    @Test
    void completeActionGateOnlyPendingAndReplayNoOp() {
        DeliveryActionExec exec = DeliveryActionExec.create(UUID.randomUUID(), 0,
                DeliveryActionType.SEND_LINK, "{\"url\":\"https://t.me/+abc\"}");

        assertTrue(exec.complete(DeliveryActionStatus.DONE, null, NOW));
        assertEquals(DeliveryActionStatus.DONE, exec.status());
        assertEquals(NOW, exec.executedAt());

        // Replay — ação já DONE → no-op.
        assertFalse(exec.complete(DeliveryActionStatus.FAILED, "x", NOW.plusSeconds(1)));
        assertEquals(DeliveryActionStatus.DONE, exec.status());

        // Status inválido.
        DeliveryActionExec other = DeliveryActionExec.create(UUID.randomUUID(), 1,
                DeliveryActionType.SEND_MESSAGE, "{\"text\":\"oi\"}");
        assertThrows(IllegalArgumentException.class,
                () -> other.complete(DeliveryActionStatus.PENDING, null, NOW));
    }

    @Test
    void evaluateAllDoneDelivers() {
        PlanDelivery delivery = delivery();
        List<DeliveryActionExec> execs = List.of(
                exec(0, DeliveryActionStatus.DONE),
                exec(1, DeliveryActionStatus.DONE));

        assertTrue(delivery.evaluate(execs, NOW));
        assertEquals(PlanDeliveryStatus.DELIVERED, delivery.status());
        assertEquals(NOW, delivery.deliveredAt());
    }

    @Test
    void evaluateAnyFailedFails() {
        PlanDelivery delivery = delivery();
        List<DeliveryActionExec> execs = List.of(
                exec(0, DeliveryActionStatus.DONE),
                exec(1, DeliveryActionStatus.FAILED));

        assertTrue(delivery.evaluate(execs, NOW));
        assertEquals(PlanDeliveryStatus.FAILED, delivery.status());
    }

    @Test
    void evaluateKeepsPendingWhileActionsRemain() {
        PlanDelivery delivery = delivery();
        List<DeliveryActionExec> execs = List.of(
                exec(0, DeliveryActionStatus.DONE),
                exec(1, DeliveryActionStatus.PENDING));

        assertFalse(delivery.evaluate(execs, NOW));
        assertEquals(PlanDeliveryStatus.PENDING, delivery.status());
    }

    @Test
    void retryReopensFailedDeliveryToPending() {
        PlanDelivery delivery = delivery();
        delivery.evaluate(List.of(exec(0, DeliveryActionStatus.FAILED)), NOW);
        assertEquals(PlanDeliveryStatus.FAILED, delivery.status());

        assertTrue(delivery.reopenForRetry());
        assertEquals(PlanDeliveryStatus.PENDING, delivery.status());
        // Idempotente — PENDING não reabre de novo.
        assertFalse(delivery.reopenForRetry());
    }

    @Test
    void execReopenOnlyFromFailed() {
        DeliveryActionExec exec = DeliveryActionExec.create(UUID.randomUUID(), 0,
                DeliveryActionType.SEND_FILE, "{\"fileUrl\":\"https://cdn/x.zip\"}");
        assertFalse(exec.reopenForRetry()); // PENDING → nada

        exec.complete(DeliveryActionStatus.FAILED, "erro", NOW);
        assertTrue(exec.reopenForRetry());
        assertEquals(DeliveryActionStatus.PENDING, exec.status());
        assertEquals(null, exec.note());
        assertEquals(null, exec.executedAt());
    }

    @Test
    void createWithProofsExposesGetters() {
        // Mutations (NullReturnVals/EmptyObjectReturnVals L153/L169/L177/L186/
        // L191/L196): getters das provas de entrega expostos (mutantes → null/"").
        Instant termsAcceptedAt = NOW.minusSeconds(30);
        PlanDelivery delivery = PlanDelivery.create(STORE_ID, PLAN_ID, TX_ID,
                Platform.DISCORD, "123456789012345678", "hash-buyer", NOW,
                termsAcceptedAt, "203.0.113.9", "Mozilla/5.0");

        assertEquals(PLAN_ID, delivery.planId());
        assertEquals("hash-buyer", delivery.buyerIdHash());
        assertEquals(NOW, delivery.createdAt());
        assertEquals(termsAcceptedAt, delivery.termsAcceptedAt());
        assertEquals("203.0.113.9", delivery.buyerIp());
        assertEquals("Mozilla/5.0", delivery.buyerDevice());
    }

    @Test
    void evaluateNoOpWhenNotPending() {
        // Mutation (BooleanTrueReturnVals L117): evaluate numa entrega JÁ
        // DELIVERED é no-op (o mutante sempre retornaria true).
        PlanDelivery delivery = delivery();
        delivery.markDelivered(NOW);

        assertFalse(delivery.evaluate(List.of(exec(0, DeliveryActionStatus.DONE)), NOW));
        assertEquals(PlanDeliveryStatus.DELIVERED, delivery.status());
    }
}
