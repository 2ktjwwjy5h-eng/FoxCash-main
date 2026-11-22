package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Request de edição do bot de venda (Spec 74) — apenas o {@code displayName} é
 * editável nesta spec (o token nunca é alterado pelo PUT).
 */
public record StoreBotUpdateRequest(String displayName) {
}
