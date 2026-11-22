package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request de confirmação de código MFA (Spec 44) — usado em
 * {@code confirm} (ativação) e {@code regenerate-recovery-codes}.
 */
public record MfaCodeRequest(
        @NotBlank String code) {
}
