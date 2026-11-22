package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.UUID;

/**
 * Resposta de criacao de chave de API (Spec 24): o segredo em claro e
 * devolvido UMA unica vez — nunca e persistido nem reexibido.
 */
public record CreateApiKeyResponse(UUID id, String secret) {
}
