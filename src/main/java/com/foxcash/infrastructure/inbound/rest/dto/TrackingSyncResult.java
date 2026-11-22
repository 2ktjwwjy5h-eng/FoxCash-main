package com.foxcash.infrastructure.inbound.rest.dto;

/** Resultado do teste de conexão de um provedor ({@code POST /{id}/sync} — Spec 81/D8). */
public record TrackingSyncResult(String service, boolean ok, String message) {
}
