package com.foxcash.domain.port.in;

import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageAudience;
import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: gestão das mensagens da loja pelo portal (Spec 51),
 * {@code ROLE_MERCHANT_PORTAL}. Tudo por {@code merchantId} do principal — a
 * loja (1:1) é resolvida internamente; nunca por id solto (404 cross-loja).
 *
 * <p>Mensagens por etapa: upsert por {@code event} (1 por etapa — 201 na
 * criação, 200 na atualização). Agendamento: one-shot ({@code scheduledAt}) ou
 * recorrente ({@code cron} 6 campos); {@code audience} = BUYERS nesta versão.
 * Auditoria {@code MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED} (Spec 41) — o
 * {@code text} nunca vai em claro para a trilha.
 */
public interface StoreMessageManagementUseCase {

    /** Mensagens da loja (portal). */
    List<StoreMessage> list(UUID merchantId);

    /** 404 {@code message_not_found} se não existe na loja do lojista. */
    StoreMessage get(UUID merchantId, UUID messageId);

    /**
     * Upsert por {@code event}: cria (201) se não existe; atualiza (200) se já
     * existe (1 mensagem por etapa).
     */
    UpsertResult upsert(UUID merchantId, SaveMessageCommand command);

    /** Atualiza text/active (campos nulos = não alterar); 404 {@code message_not_found}. */
    StoreMessage update(UUID merchantId, UUID messageId, SaveMessageCommand command);

    /** 204 — remove a mensagem da etapa. */
    void delete(UUID merchantId, UUID messageId);

    /** Agenda um envio (one-shot ou cron) — 201; auditoria {@code MESSAGE_SCHEDULED}. */
    ScheduledMessage schedule(UUID merchantId, ScheduleMessageCommand command);

    /** Mensagem da etapa: event + text (1–1000) + active. */
    record SaveMessageCommand(StoreMessageEvent event, String text, Boolean active) {
    }

    /** Agendamento: text + exatamente um de scheduledAt/cron + audience (BUYERS). */
    record ScheduleMessageCommand(String text, Instant scheduledAt, String cron,
                                  ScheduledMessageAudience audience) {
    }

    /** Resultado do upsert: a mensagem e se foi criada (201) ou atualizada (200). */
    record UpsertResult(StoreMessage message, boolean created) {
    }
}
