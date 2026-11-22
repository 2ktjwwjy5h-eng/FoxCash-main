package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Resposta de mensagem da loja (Spec 51) — devolvida na API do portal (texto
 * em claro ao dono; nunca em claro na trilha/logs).
 */
public record StoreMessageResponse(
        UUID id,
        StoreMessageEvent event,
        String text,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static StoreMessageResponse from(StoreMessage message) {
        return new StoreMessageResponse(
                message.id(), message.event(), message.text(), message.active(),
                message.createdAt(), message.updatedAt());
    }
}
