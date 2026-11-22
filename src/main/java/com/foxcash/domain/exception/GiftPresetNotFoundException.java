package com.foxcash.domain.exception;

/**
 * Preset de Gift PIX inexistente (Spec 23) — mapeada para 404
 * {@code gift_preset_not_found}.
 */
public class GiftPresetNotFoundException extends RuntimeException {

    public GiftPresetNotFoundException(java.util.UUID presetId) {
        super("Gift preset not found: " + presetId);
    }
}
