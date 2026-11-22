package com.foxcash.domain.model.delivery;

import com.foxcash.domain.model.plan.DeliveryActionType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ação de entrega em EXECUÇÃO (Spec 49) — snapshot da ação do plano na data do
 * pagamento, com progresso individual. O payload (criptografado em repouso)
 * é copiado do plano para que a entrega não mude se o vendedor editar o plano
 * depois da venda.
 */
public class DeliveryActionExec {

    private final UUID id;
    private final UUID deliveryId;
    private final int seq;
    private final DeliveryActionType actionType;
    private final String payloadSnapshot;
    private DeliveryActionStatus status;
    private String note;
    private Instant executedAt;

    private DeliveryActionExec(UUID id, UUID deliveryId, int seq, DeliveryActionType actionType,
                               String payloadSnapshot, DeliveryActionStatus status,
                               String note, Instant executedAt) {
        this.id = id;
        this.deliveryId = deliveryId;
        this.seq = seq;
        this.actionType = actionType;
        this.payloadSnapshot = payloadSnapshot;
        this.status = status;
        this.note = note;
        this.executedAt = executedAt;
    }

    public static DeliveryActionExec create(UUID deliveryId, int seq, DeliveryActionType actionType,
                                            String payloadSnapshot) {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        if (seq < 0) {
            throw new IllegalArgumentException("seq deve ser >= 0");
        }
        if (payloadSnapshot == null || payloadSnapshot.isBlank()) {
            throw new IllegalArgumentException("payloadSnapshot e obrigatorio");
        }
        return new DeliveryActionExec(UUID.randomUUID(), deliveryId, seq, actionType,
                payloadSnapshot, DeliveryActionStatus.PENDING, null, null);
    }

    public static DeliveryActionExec restore(UUID id, UUID deliveryId, int seq,
                                             DeliveryActionType actionType, String payloadSnapshot,
                                             DeliveryActionStatus status, String note, Instant executedAt) {
        return new DeliveryActionExec(id, deliveryId, seq, actionType, payloadSnapshot,
                status, note, executedAt);
    }

    /**
     * Conclui a ação (gate: só PENDING → DONE|FAILED). Replay (status ≠ PENDING)
     * é no-op e retorna {@code false} (204 idempotente).
     */
    public boolean complete(DeliveryActionStatus target, String note, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (target != DeliveryActionStatus.DONE && target != DeliveryActionStatus.FAILED) {
            throw new IllegalArgumentException("status invalido para a acao: " + target);
        }
        if (status != DeliveryActionStatus.PENDING) {
            return false;
        }
        this.status = target;
        this.note = note;
        this.executedAt = now;
        return true;
    }

    /** Reabre a ação FAILED como PENDING (retry) — gate: só FAILED. */
    public boolean reopenForRetry() {
        if (status != DeliveryActionStatus.FAILED) {
            return false;
        }
        this.status = DeliveryActionStatus.PENDING;
        this.note = null;
        this.executedAt = null;
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID deliveryId() {
        return deliveryId;
    }

    public int seq() {
        return seq;
    }

    public DeliveryActionType actionType() {
        return actionType;
    }

    public String payloadSnapshot() {
        return payloadSnapshot;
    }

    public DeliveryActionStatus status() {
        return status;
    }

    public String note() {
        return note;
    }

    public Instant executedAt() {
        return executedAt;
    }
}
