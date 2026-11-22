package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request de {@code POST /api/v1/merchant-auth/reset-password} (Spec 40) — consome
 * o token one-time {@code rst_<32 hex>} e redefine a senha (8–72 caracteres).
 */
public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
