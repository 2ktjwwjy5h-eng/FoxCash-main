package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request de desativação do MFA (Spec 44) — exige senha atual + TOTP válido
 * (ou recovery code) para evitar lockout/roubo de sessão.
 */
public record MfaDeactivateRequest(
        @NotBlank String code,
        @NotBlank String password) {
}
