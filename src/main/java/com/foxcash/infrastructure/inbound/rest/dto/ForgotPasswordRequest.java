package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request de {@code POST /api/v1/merchant-auth/forgot-password} (Spec 40) —
 * inicia a recuperação de senha pelo e-mail (identificador de login).
 */
public record ForgotPasswordRequest(
        @NotBlank @Email @Size(max = 255) String email) {
}
