package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Banimento de lojista (Spec 98 — D5): {@code reason} obrigatório (400
 * {@code reason_required}). Irreversível (BANNED) — não existe unbanned.
 * Ação sensível com step-up TOTP (Spec 69).
 */
public record BanRequest(String reason) {
}
