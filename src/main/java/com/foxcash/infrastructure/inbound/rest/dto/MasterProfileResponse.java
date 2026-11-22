package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MasterProfileUseCase;

import java.time.Instant;
import java.util.UUID;

/**
 * Perfil master na resposta REST (Spec 41) — nunca expõe o {@code password_hash}.
 */
public record MasterProfileResponse(
        UUID id,
        String username,
        String name,
        boolean active,
        boolean canManageMasters,
        Instant createdAt) {

    public static MasterProfileResponse from(MasterProfileUseCase.Profile profile) {
        return new MasterProfileResponse(profile.id(), profile.username(), profile.name(),
                profile.active(), profile.canManageMasters(), profile.createdAt());
    }
}
