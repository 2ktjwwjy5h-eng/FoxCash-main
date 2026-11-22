package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Resposta do setup opcional do wizard (Spec 77): {@code ok=true} quando as
 * chamadas da Bot API foram aplicadas com sucesso; falha → 400
 * {@code telegram_api_error} com a mensagem da API.
 */
public record BotSetupResponse(boolean ok) {
}
