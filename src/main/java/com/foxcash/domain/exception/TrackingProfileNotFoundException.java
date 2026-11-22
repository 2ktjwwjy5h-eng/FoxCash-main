package com.foxcash.domain.exception;

/**
 * Perfil de tracking não encontrado (Spec 81) — 404 {@code tracking_profile_not_found}.
 * Escopado por {@code merchantId} (cross-lojista → 404, sem revelar existência).
 */
public class TrackingProfileNotFoundException extends RuntimeException {
    public TrackingProfileNotFoundException() {
        super("tracking_profile_not_found");
    }
}
