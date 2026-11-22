package com.foxcash.domain.model.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento imutável da {@code platform_activity_log} (Spec 41) — relatório
 * unificado de atividades: quem (actor), o que (action), o alvo (entity +
 * merchant), contexto ({@code details} JSON serializado — sem dados sensíveis),
 * {@code ip} (null em jobs/SYSTEM). Append-only: nenhum update/delete.
 */
public record PlatformActivity(
        UUID id,
        PlatformActorType actorType,
        UUID actorId,
        PlatformAction action,
        String entityType,
        UUID entityId,
        UUID merchantId,
        String details,
        String ip,
        Instant createdAt) {

    public PlatformActivity {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actorType, "actorType must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /** Cria um evento da trilha (usada pelo recorder). */
    public static PlatformActivity record(PlatformActorType actorType, UUID actorId,
                                          PlatformAction action, String entityType, UUID entityId,
                                          UUID merchantId, String details, String ip, Instant now) {
        return new PlatformActivity(UUID.randomUUID(), actorType, actorId, action,
                entityType, entityId, merchantId, details, ip, now);
    }
}
