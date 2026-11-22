package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformActivity;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento do relatório unificado de atividades ({@code GET /api/v1/master/report/activity},
 * Spec 41).
 */
public record PlatformActivityResponse(
        UUID id,
        Instant occurredAt,
        PlatformActorType actorType,
        UUID actorId,
        PlatformAction action,
        String entityType,
        UUID entityId,
        UUID merchantId,
        String details,
        String ip) {

    public static PlatformActivityResponse from(PlatformActivity activity) {
        return new PlatformActivityResponse(
                activity.id(),
                activity.createdAt(),
                activity.actorType(),
                activity.actorId(),
                activity.action(),
                activity.entityType(),
                activity.entityId(),
                activity.merchantId(),
                activity.details(),
                activity.ip());
    }
}
