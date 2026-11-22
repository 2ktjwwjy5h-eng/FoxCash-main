package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.gift.GiftPreset;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Preset do catalogo de Gift PIX (Spec 23) — valor predefinido que o bot
 * oferece ao cliente; o lojista ativa/desativa.
 */
public record GiftPresetResponse(
        UUID id,
        BigDecimal amount,
        String label,
        boolean active) {

    public static GiftPresetResponse from(GiftPreset preset) {
        return new GiftPresetResponse(
                preset.id(),
                preset.amount().amount(),
                preset.label(),
                preset.active());
    }
}
