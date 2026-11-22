package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request de login do perfil master (Spec 13; Spec 44 — 2FA TOTP).
 *
 * <p>{@code totpCode} é opcional: exigido apenas quando o master tem MFA ativo
 * (código TOTP de 6 dígitos ou recovery code {@code mrc_...}). Sem MFA ativo,
 * o campo é ignorado — zero regressão no login atual.
 */
public record MasterLoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        String totpCode) {
}
