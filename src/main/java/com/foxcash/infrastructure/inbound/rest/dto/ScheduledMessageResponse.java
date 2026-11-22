package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageAudience;
import com.foxcash.domain.model.store.ScheduledMessageStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta de mensagem programada (Spec 51) — API do portal (lista/agendamento)
 * e da busca do bot (com {@code recipients}). O texto é em claro ao dono;
 * nunca em claro na trilha/logs.
 */
public record ScheduledMessageResponse(
        UUID id,
        String text,
        Instant scheduledAt,
        String cron,
        ScheduledMessageAudience audience,
        ScheduledMessageStatus status,
        Instant sentAt,
        Instant createdAt) {

    public static ScheduledMessageResponse from(ScheduledMessage message) {
        return new ScheduledMessageResponse(
                message.id(), message.text(), message.scheduledAt(), message.cron(),
                message.audience(), message.status(), message.sentAt(), message.createdAt());
    }
}
