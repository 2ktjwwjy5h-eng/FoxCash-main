package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Resposta da pré-validação do token do Telegram (Spec 77 — wizard): a
 * identidade do bot obtida via {@code getMe} — nome e @ — exibida ao lojista
 * antes de continuar. O token nunca é devolvido.
 */
public record ValidateBotTokenResponse(String displayName, String username) {
}
