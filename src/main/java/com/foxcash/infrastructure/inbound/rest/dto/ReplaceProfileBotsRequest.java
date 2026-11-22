package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.List;
import java.util.UUID;

/**
 * Atribuição da frota de bots a um perfil de operação (Spec 139 — endpoint novo
 * {@code PUT /api/v1/portal/profile/{id}/bots}): {@code botIds} é a lista de ids
 * dos bots do lojista — **substituição total** da frota (lista vazia esvazia).
 * Bot de outro lojista/deletado → 400 {@code bot_not_found}; perfil
 * GATEWAY_ONLY → 400 {@code bots_not_allowed_for_scope}.
 */
public record ReplaceProfileBotsRequest(
        List<UUID> botIds) {
}
