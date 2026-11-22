package com.foxcash.domain.port.out;

import com.foxcash.domain.model.gift.GiftPreset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia do catalogo de Gift PIX do lojista (Spec 23).
 */
public interface GiftPresetRepository {

    GiftPreset save(GiftPreset preset);

    /** Presets do lojista ordenados por valor (crescente). */
    List<GiftPreset> findByMerchantId(UUID merchantId);

    Optional<GiftPreset> findByIdAndMerchantId(UUID id, UUID merchantId);
}
