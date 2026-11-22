package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Resposta de botão do menu (Spec 50) — devolvida na API do portal (payload em
 * claro ao dono; criptografado em repouso) e no storefront (apenas ativos,
 * ordenados — o bot renderiza o menu com o payload).
 */
public record StoreButtonResponse(
        UUID id,
        String label,
        StoreButtonAction action,
        Map<String, Object> payload,
        int position,
        boolean active,
        Instant createdAt) {

    public static StoreButtonResponse from(StoreButton button, Map<String, Object> payload) {
        return new StoreButtonResponse(
                button.id(), button.label(), button.action(), payload,
                button.position(), button.active(), button.createdAt());
    }
}
