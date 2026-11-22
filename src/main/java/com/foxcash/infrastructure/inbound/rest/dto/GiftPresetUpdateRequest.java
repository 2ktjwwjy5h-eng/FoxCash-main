package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Request de ativacao/desativacao de um preset de Gift PIX (Spec 23).
 */
public record GiftPresetUpdateRequest(boolean active) {
}
