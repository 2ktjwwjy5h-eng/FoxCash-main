package com.foxcash.domain.model.tracking;

import com.foxcash.domain.model.botfunnel.UtmParams;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de tracking normalizado (Spec 81/D6) — o que cada provedor recebe.
 * {@code value} é nulo para {@code LEAD} (sem transação); {@code platformUserId}
 * é o ID opaco do usuário na plataforma (nunca dado pessoal em claro).
 */
public record TrackingEvent(
        UUID profileId,
        UUID merchantId,
        UUID botId,
        String eventType,          // LEAD / INITIATE_CHECKOUT / PURCHASE
        UUID eventId,              // id estável do evento do funil (Spec 75)
        String platformUserId,     // ID opaco da plataforma (nulo em checkout sem user)
        BigDecimal value,          // nulo para LEAD
        String currency,           // "BRL"
        UtmParams utm,
        Instant eventTime) {

    public TrackingEvent {
        if (eventType == null || eventId == null || merchantId == null || botId == null) {
            throw new IllegalArgumentException("eventType/eventId/merchantId/botId sao obrigatorios");
        }
    }
}
