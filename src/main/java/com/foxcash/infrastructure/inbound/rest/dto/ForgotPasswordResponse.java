package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Response de {@code POST /api/v1/merchant-auth/forgot-password} (Spec 40).
 *
 * <p>{@code resetToken} só é devolvido quando {@code foxcash.auth.expose-reset-token=true}
 * (dev) — em produção a resposta é sempre 204 e este corpo nunca é emitido
 * (anti-enumeração).
 */
public record ForgotPasswordResponse(String resetToken) {
}
