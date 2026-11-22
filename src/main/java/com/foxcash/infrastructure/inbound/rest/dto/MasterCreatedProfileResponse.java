package com.foxcash.infrastructure.inbound.rest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response de {@code POST /api/v1/master/profiles} (Spec 41): perfil criado +
 * {@code temporaryPassword} (senha gerada pelo sistema, devolvida UMA vez).
 */
public record MasterCreatedProfileResponse(
        UUID id,
        String username,
        String name,
        boolean active,
        boolean canManageMasters,
        Instant createdAt,
        String temporaryPassword) {

    public static MasterCreatedProfileResponse from(MasterProfileResponse profile, String temporaryPassword) {
        return new MasterCreatedProfileResponse(profile.id(), profile.username(), profile.name(),
                profile.active(), profile.canManageMasters(), profile.createdAt(), temporaryPassword);
    }
}
