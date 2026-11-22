package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request de troca de senha do perfil master (SEC-25, revisão de segurança
 * 2026-08-11). Exige a senha ATUAL (verificada com BCrypt no serviço — a
 * política completa de complexidade é aplicada por {@code PasswordPolicy} no
 * serviço, não só pelo Bean Validation aqui).
 */
public record MasterChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
