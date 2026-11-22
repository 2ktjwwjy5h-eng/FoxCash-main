package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.List;
import java.util.UUID;

/**
 * Criação/atualização de perfil de tracking (Spec 81/D7). Token nulo/em branco
 * no PUT = manter o existente (os tokens nunca são devolvidos pela API — padrão
 * das credenciais da Spec 35). {@code botIds} substitui os vínculos atuais.
 */
public record TrackingProfileRequest(
        String name,
        String facebookPixelId,
        String facebookAccessToken,
        String tiktokPixelId,
        String tiktokAccessToken,
        String utmifyToken,
        String otimizeyToken,
        List<UUID> botIds) {

    public TrackingProfileRequest {
        botIds = botIds == null ? List.of() : botIds;
    }
}
