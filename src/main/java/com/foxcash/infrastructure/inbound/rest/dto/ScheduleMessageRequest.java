package com.foxcash.infrastructure.inbound.rest.dto;

import java.time.Instant;

/**
 * Request de agendamento de mensagem (Spec 51) — {@code text} (1–1000) e
 * exatamente um de {@code scheduledAt} (one-shot) ou {@code cron} (recorrente,
 * 6 campos Quartz). {@code audience} = BUYERS nesta versão (null/blank default
 * BUYERS; outro valor → 400). O backend resolve os destinatários na busca do
 * bot; a confirmação do envio é {@code POST /foxcash/scheduled-messages/{id}/sent}.
 */
public record ScheduleMessageRequest(
        String text,
        Instant scheduledAt,
        String cron,
        String audience) {
}
