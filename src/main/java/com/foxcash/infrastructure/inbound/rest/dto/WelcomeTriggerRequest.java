package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.UUID;

/**
 * Requisição do evento de entrada de membro (Spec 79/D6) — {@code POST
 * /api/v1/foxcash/broadcasts/welcome-trigger}. O bot informa o evento (Telegram
 * {@code chat_member} / Discord {@code guildMemberAdd}) e o backend devolve a
 * mensagem WELCOME ativa do chat (idempotente por {@code (chatId, userId)}).
 */
public record WelcomeTriggerRequest(
        UUID botId,
        String chatId,
        String userId) {
}
