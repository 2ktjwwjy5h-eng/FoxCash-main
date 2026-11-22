package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request de {@code POST /api/v1/master/profiles} (Spec 41) — criação de perfil
 * master (senha gerada pelo sistema; apenas o username é informado).
 */
public record MasterProfileRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(max = 120) String name) {
}
