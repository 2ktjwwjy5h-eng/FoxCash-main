package com.foxcash.domain.model.botfunnel;

import com.foxcash.domain.model.crm.CrmLeadStatus;
import com.foxcash.domain.model.crm.LeadHandoffStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lead de um bot de venda (Spec 75) — um usuário único que inicia conversa com
 * o bot. A unicidade é {@code (bot_id, platform_user_id)} (constraint UNIQUE na
 * migration V50): replay de update (Telegram) / interação (Discord) não duplica.
 *
 * <p>Value object imutável: {@code firstSeenAt} é a primeira vez que o usuário
 * foi visto (criado no registro; replay nunca sobrescreve). Nenhum dado pessoal
 * além do {@code platform_user_id} (ID opaco da plataforma — criptografado em
 * repouso por convenção da Spec 74/52; o ID é armazenado como o resto do
 * destinatário do bot).
 *
 * <p>{@code utm} (Spec 81/D4): parâmetros UTM capturados no início da conversa —
 * "primeiro valor vence" (gravados na CRIAÇÃO; replay nunca sobrescreve).
 *
 * <p>Specs 83/90 (CRM): o lead ganha os campos de perfil ({@code name},
 * {@code username}, {@code email}, {@code phone}), o {@code status} do CRM
 * (derivado do funil — {@link CrmLeadStatus}) e o {@code handoffStatus}
 * ({@link LeadHandoffStatus} — atendimento manual por lead). O {@code id}
 * persistido ({@code bot_leads.id}) é referenciado por
 * {@code chat_messages.lead_id} (Spec 83). Os campos novos são opcionais no
 * construtor (defaults: status {@code NOVO}, handoff {@code BOT}) para não
 * quebrar os criadores do funil.
 */
public record BotLead(
        UUID id,
        UUID botId,
        String platformUserId,
        Instant firstSeenAt,
        Instant createdAt,
        UtmParams utm,
        String name,
        String username,
        String email,
        String phone,
        CrmLeadStatus status,
        LeadHandoffStatus handoffStatus) {

    public BotLead {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(platformUserId, "platformUserId must not be null");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (platformUserId.isBlank()) {
            throw new IllegalArgumentException("platformUserId nao pode ser vazio");
        }
        if (status == null) {
            status = CrmLeadStatus.NOVO;
        }
        if (handoffStatus == null) {
            handoffStatus = LeadHandoffStatus.BOT;
        }
    }

    /** Registro de lead (primeira vez que o usuário foi visto) — sem UTM. */
    public static BotLead create(UUID botId, String platformUserId, Instant now) {
        return create(botId, platformUserId, null, now);
    }

    /** Registro de lead com UTMs do link de entrada (Spec 81/D4 — primeiro valor vence). */
    public static BotLead create(UUID botId, String platformUserId, UtmParams utm, Instant now) {
        return new BotLead(UUID.randomUUID(), botId, platformUserId, now, now, utm,
                null, null, null, null, CrmLeadStatus.NOVO, LeadHandoffStatus.BOT);
    }

    /** Restaura do banco (o dado persistido é confiável) — funil (sem campos CRM). */
    public static BotLead restore(UUID id, UUID botId, String platformUserId,
                                  Instant firstSeenAt, Instant createdAt, UtmParams utm) {
        return new BotLead(id, botId, platformUserId, firstSeenAt, createdAt, utm,
                null, null, null, null, CrmLeadStatus.NOVO, LeadHandoffStatus.BOT);
    }

    /** Restaura do banco com os campos do CRM (Spec 83/90) — usado pelo mapper. */
    public static BotLead restoreCrm(UUID id, UUID botId, String platformUserId,
                                     Instant firstSeenAt, Instant createdAt, UtmParams utm,
                                     String name, String username, String email, String phone,
                                     CrmLeadStatus status, LeadHandoffStatus handoffStatus) {
        return new BotLead(id, botId, platformUserId, firstSeenAt, createdAt, utm,
                name, username, email, phone, status, handoffStatus);
    }

    // ---- Transições (Spec 90 — handoff humano por lead) ----

    /**
     * Pausa o bot para ESTE lead ({@code BOT → HUMAN}) — idempotente: repetir
     * {@code HUMAN} é no-op (retorna {@code this}).
     */
    public BotLead pauseBot() {
        if (handoffStatus == LeadHandoffStatus.HUMAN) {
            return this;
        }
        return new BotLead(id, botId, platformUserId, firstSeenAt, createdAt, utm,
                name, username, email, phone, status, LeadHandoffStatus.HUMAN);
    }

    /**
     * Retoma o bot ({@code HUMAN → BOT}) — idempotente: repetir {@code BOT} é
     * no-op (retorna {@code this}).
     */
    public BotLead resumeBot() {
        if (handoffStatus == LeadHandoffStatus.BOT) {
            return this;
        }
        return new BotLead(id, botId, platformUserId, firstSeenAt, createdAt, utm,
                name, username, email, phone, status, LeadHandoffStatus.BOT);
    }

    /** Cópia com status do CRM atualizado (Spec 83 D4 — derivação na mesma transação). */
    public BotLead withCrmStatus(CrmLeadStatus newStatus) {
        if (status == newStatus) {
            return this;
        }
        return new BotLead(id, botId, platformUserId, firstSeenAt, createdAt, utm,
                name, username, email, phone, newStatus, handoffStatus);
    }
}
