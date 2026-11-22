package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.gift.GiftPreset;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: catalogo de Gift PIX do lojista (Spec 23) — valores
 * predefinidos (defaults R$ 5/10/20/50) que o bot oferece ao cliente, com
 * opcao personalizado. O lojista ativa/desativa presets via portal.
 */
public interface GiftPresetUseCase {

    List<GiftPreset> presets(UUID merchantId);

    /** Ativa/desativa um preset do lojista; 404 {@code gift_preset_not_found} se inexistente. */
    void setActive(UUID merchantId, UUID presetId, boolean active);

    /**
     * Gera cobranca pelo preset selecionado (ou valor personalizado) — reusa o
     * {@link CreatePixChargeUseCase} (Spec 19), com triagem antifraude ativa.
     */
    CreatePixChargeUseCase.Result giftCharge(UUID merchantId, GiftChargeCommand command);

    /**
     * {@code presetId} XOR {@code amount} — um dos dois deve vir preenchido.
     * {@code idempotencyKey} (Spec 28) e a chave de idempotência OBRIGATÓRIA,
     * repassada ao {@link CreatePixChargeUseCase}.
     */
    record GiftChargeCommand(UUID presetId, Money amount, Integer expirationSeconds,
                             String buyerId, String description, String idempotencyKey) {
    }
}
