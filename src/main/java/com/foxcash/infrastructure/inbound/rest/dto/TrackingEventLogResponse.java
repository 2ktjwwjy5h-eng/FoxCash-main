package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.tracking.EventLog;

import java.time.Instant;
import java.util.UUID;

/** Linha do log de disparos de tracking (Spec 81/D7 — GET /{id}/events). */
public record TrackingEventLogResponse(
        String eventType,
        UUID eventId,
        String status,
        String responsePayload,
        Instant createdAt) {

    public static TrackingEventLogResponse from(EventLog log) {
        return new TrackingEventLogResponse(log.eventType(), log.eventId(),
                log.status().name(), log.responsePayload(), log.createdAt());
    }
}
