package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.List;
import java.util.UUID;

/**
 * Request de reordenação do menu (Spec 50) — {@code ids[]} na ordem desejada
 * (todos os botões da loja; posições recalculadas 0..N).
 */
public record ReorderButtonsRequest(List<UUID> ids) {
}
