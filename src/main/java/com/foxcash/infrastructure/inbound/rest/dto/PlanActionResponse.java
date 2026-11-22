package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.DeliveryActionType;

import java.util.Map;
import java.util.UUID;

/**
 * Resposta de ação de entrega (Spec 49) — devolvida apenas na API de ações do
 * próprio lojista (payload em claro ao dono; criptografado em repouso).
 */
public record PlanActionResponse(
        UUID id,
        int seq,
        DeliveryActionType type,
        Map<String, Object> payload) {

    public static PlanActionResponse from(DeliveryAction action, Map<String, Object> payload) {
        return new PlanActionResponse(action.id(), action.seq(), action.actionType(), payload);
    }
}
