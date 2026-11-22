package com.foxcash.domain.model.plan;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ação de entrega da configuração do plano (Spec 49) — define O QUE acontece
 * quando o pagamento é aprovado. O {@code payload} é um JSON tipado
 * (ex.: {@code {"roleId":"..."}}), validado por {@link DeliveryActionType} na
 * camada de aplicação e **criptografado em repouso** na persistência
 * (CryptoConverter — nunca em claro no banco).
 */
public class DeliveryAction {

    private final UUID id;
    private final UUID planId;
    private final int seq;
    private final DeliveryActionType actionType;
    private final String payload;
    private final Instant createdAt;

    private DeliveryAction(UUID id, UUID planId, int seq, DeliveryActionType actionType,
                           String payload, Instant createdAt) {
        this.id = id;
        this.planId = planId;
        this.seq = seq;
        this.actionType = actionType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public static DeliveryAction create(UUID planId, int seq, DeliveryActionType actionType,
                                        String payload, Instant now) {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (seq < 0) {
            throw new IllegalArgumentException("seq deve ser >= 0");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload e obrigatorio na acao de entrega");
        }
        return new DeliveryAction(UUID.randomUUID(), planId, seq, actionType, payload, now);
    }

    public static DeliveryAction restore(UUID id, UUID planId, int seq,
                                         DeliveryActionType actionType, String payload,
                                         Instant createdAt) {
        return new DeliveryAction(id, planId, seq, actionType, payload, createdAt);
    }

    /**
     * Nova instância com o MESMO id/planId e os novos valores (update — o save
     * posterior vira UPDATE via merge; o id e o createdAt permanecem estáveis).
     */
    public DeliveryAction withValues(int newSeq, DeliveryActionType newType, String newPayload) {
        return new DeliveryAction(id, planId, newSeq, newType, newPayload, createdAt);
    }

    public UUID id() {
        return id;
    }

    public UUID planId() {
        return planId;
    }

    public int seq() {
        return seq;
    }

    public DeliveryActionType actionType() {
        return actionType;
    }

    public String payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
