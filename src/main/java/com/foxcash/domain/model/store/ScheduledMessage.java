package com.foxcash.domain.model.store;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Mensagem programada da loja (Spec 51) — o vendedor agenda envios em massa
 * (ex.: promoção): one-shot com {@code scheduledAt} OU recorrente com
 * {@code cron} (6 campos, Quartz-compatível — validado na criação). O backend
 * mantém o agendamento (SCHEDULED) e o bot (Spec 52) consome as vencidas via
 * {@code GET /api/v1/foxcash/scheduled-messages?dueBefore=} e confirma o envio
 * ({@code POST /{id}/sent} → SENT, idempotente).
 *
 * <p>O {@code text} pode conter link privado — nunca vai em claro para a
 * trilha de auditoria/logs (a atividade registra apenas audience/tipo). Os
 * destinatários ({@code recipients}) são resolvidos na consulta (não
 * persistidos aqui).
 */
public class ScheduledMessage {

    public static final int MAX_TEXT = 1000;
    private static final int MAX_CRON = 100;

    private final UUID id;
    private final UUID storeId;
    private final String text;
    private final Instant scheduledAt;
    private final String cron;
    private final ScheduledMessageAudience audience;
    private ScheduledMessageStatus status;
    private Instant sentAt;
    private final Instant createdAt;

    private ScheduledMessage(UUID id, UUID storeId, String text, Instant scheduledAt, String cron,
                             ScheduledMessageAudience audience, ScheduledMessageStatus status,
                             Instant sentAt, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.text = text;
        this.scheduledAt = scheduledAt;
        this.cron = cron;
        this.audience = audience;
        this.status = status;
        this.sentAt = sentAt;
        this.createdAt = createdAt;
    }

    /**
     * Cria a mensagem programada (validações aplicadas; SCHEDULED).
     * Exatamente um de {@code scheduledAt}/{@code cron} deve ser informado
     * (XOR — ambos ou nenhum → 400 {@code invalid_argument}).
     */
    public static ScheduledMessage create(UUID storeId, String text, Instant scheduledAt, String cron,
                                          ScheduledMessageAudience audience, Instant now) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        validate(text, scheduledAt, cron);
        return new ScheduledMessage(UUID.randomUUID(), storeId, text, scheduledAt, cron,
                audience == null ? ScheduledMessageAudience.BUYERS : audience,
                ScheduledMessageStatus.SCHEDULED, null, now);
    }

    /** Restaura do banco (sem validações — o dado persistido é confiável). */
    public static ScheduledMessage restore(UUID id, UUID storeId, String text, Instant scheduledAt,
                                           String cron, ScheduledMessageAudience audience,
                                           ScheduledMessageStatus status, Instant sentAt,
                                           Instant createdAt) {
        return new ScheduledMessage(id, storeId, text, scheduledAt, cron, audience, status, sentAt, createdAt);
    }

    /**
     * Marca como enviada (confirmação do bot). Gate: só SCHEDULED transiciona
     * (replay → 204 no-op). {@code sentAt} registrado na confirmação.
     */
    public boolean markSent(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (status != ScheduledMessageStatus.SCHEDULED) {
            return false;
        }
        this.status = ScheduledMessageStatus.SENT;
        this.sentAt = now;
        return true;
    }

    /**
     * A mensagem está vencida (o bot pode buscar) em {@code dueBefore}?
     * One-shot: {@code scheduled_at ≤ dueBefore}. Recorrente: o próximo
     * disparo do cron após a criação ≤ {@code dueBefore}. O cron é avaliado
     * em {@code ZonedDateTime} (UTC) — o {@code CronExpression.next} exige
     * um temporal com campo {@code DAY_OF_WEEK} (Instant não suporta).
     */
    public boolean isDue(Instant dueBefore) {
        if (status != ScheduledMessageStatus.SCHEDULED) {
            return false;
        }
        Objects.requireNonNull(dueBefore, "dueBefore must not be null");
        if (scheduledAt != null) {
            return !scheduledAt.isAfter(dueBefore);
        }
        ZonedDateTime nextFire = CronExpression.parse(cron).next(createdAt.atZone(ZoneOffset.UTC));
        return nextFire != null && !nextFire.toInstant().isAfter(dueBefore);
    }

    private static void validate(String text, Instant scheduledAt, String cron) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text e obrigatorio na mensagem programada");
        }
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("text deve ter no maximo " + MAX_TEXT + " caracteres");
        }
        // XOR: exatamente um de scheduledAt/cron.
        if ((scheduledAt == null) == (cron == null)) {
            throw new IllegalArgumentException(
                    "informe exatamente um de scheduledAt (one-shot) ou cron (recorrente)");
        }
        if (cron != null) {
            if (cron.isBlank() || cron.length() > MAX_CRON) {
                throw new IllegalArgumentException("cron deve ter no maximo " + MAX_CRON + " caracteres");
            }
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("cron invalida (formato Quartz de 6 campos): " + cron);
            }
        }
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public String text() {
        return text;
    }

    /** Null quando {@code cron} está definido (one-shot). */
    public Instant scheduledAt() {
        return scheduledAt;
    }

    /** Null quando {@code scheduledAt} está definido (recorrente). */
    public String cron() {
        return cron;
    }

    public ScheduledMessageAudience audience() {
        return audience;
    }

    public ScheduledMessageStatus status() {
        return status;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
