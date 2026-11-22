package com.foxcash.domain.model.crm;

/**
 * Remetente de uma mensagem do chat do CRM (Spec 83) — a origem da bolha no
 * Live Chat: {@code CLIENT} (recebida do cliente via webhook/listener),
 * {@code ADMIN} (enviada pelo painel do lojista) e {@code BOT_AUTO} (mensagens
 * automáticas do funil/agendamento — Spec 51/79).
 */
public enum ChatSenderType {

    /** Mensagem recebida do cliente (webhook Telegram / interação Discord). */
    CLIENT,

    /** Mensagem enviada pelo lojista no painel (REST, auditável — Spec 41). */
    ADMIN,

    /** Mensagem automática do bot (agendadas Spec 51, broadcast WELCOME Spec 79). */
    BOT_AUTO
}
