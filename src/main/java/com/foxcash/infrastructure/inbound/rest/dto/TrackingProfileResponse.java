package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.model.tracking.TrackingServiceType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resposta de perfil de tracking (Spec 81/D7) — **tokens nunca em claro**, só
 * máscara (últimos 4); badges por provedor configurado e resumo do log
 * (ok/erros) para os cards do painel (Spec 82).
 */
public record TrackingProfileResponse(
        UUID id,
        String name,
        String status,
        String facebookPixelId,
        String facebookTokenMask,
        String tiktokPixelId,
        String tiktokTokenMask,
        String utmifyTokenMask,
        String otimizeyTokenMask,
        List<String> services,
        List<UUID> botIds,
        long okEvents,
        long errorEvents,
        java.time.Instant createdAt,
        java.time.Instant updatedAt) {

    public static TrackingProfileResponse from(TrackingProfile profile, Set<UUID> botIds,
                                               long okEvents, long errorEvents) {
        List<String> services = new java.util.ArrayList<>();
        if (profile.isFacebookConfigured()) {
            services.add("facebook");
        }
        if (profile.isTikTokConfigured()) {
            services.add("tiktok");
        }
        if (profile.isUtmifyConfigured()) {
            services.add("utmify");
        }
        if (profile.isOtimizeyConfigured()) {
            services.add("otimizey");
        }
        return new TrackingProfileResponse(
                profile.id(), profile.name(), profile.status().name(),
                profile.facebookPixelId(),
                TrackingProfile.tokenMask(profile.facebookAccessToken()),
                profile.tiktokPixelId(),
                TrackingProfile.tokenMask(profile.tiktokAccessToken()),
                TrackingProfile.tokenMask(profile.utmifyToken()),
                TrackingProfile.tokenMask(profile.otimizeyToken()),
                services,
                botIds.stream().sorted().collect(Collectors.toList()),
                okEvents, errorEvents,
                profile.createdAt(), profile.updatedAt());
    }
}
