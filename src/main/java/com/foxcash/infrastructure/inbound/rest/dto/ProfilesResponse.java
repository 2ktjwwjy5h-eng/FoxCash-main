package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.List;
import java.util.UUID;

/**
 * Resposta do {@code GET /api/v1/portal/profile} (Spec 122): todos os perfis do
 * lojista + o perfil ativo (do header {@code X-Profile-ID} validado ou default).
 */
public record ProfilesResponse(
        List<ProfileResponse> profiles,
        UUID activeProfileId) {
}
