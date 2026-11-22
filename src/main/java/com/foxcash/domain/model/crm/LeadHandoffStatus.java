package com.foxcash.domain.model.crm;

/**
 * Modo de atendimento de um lead no CRM (Spec 90) — {@code BOT} (automático,
 * comportamento padrão) vs {@code HUMAN} (atendimento manual: o backend
 * consome a mensagem do cliente para o CRM e aborta o encaminhamento ao funil
 * do bot). Default {@code BOT}; o lojista alterna por lead via
 * {@code PUT /api/v1/portal/crm/leads/{id}/handoff}.
 */
public enum LeadHandoffStatus {

    /** Bot responde normalmente (funil ativo). */
    BOT,

    /** Atendimento manual — bot pausado para ESTE cliente. */
    HUMAN
}
