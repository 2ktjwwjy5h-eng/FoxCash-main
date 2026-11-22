package com.foxcash.application.service;

import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.exception.GiftPresetNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.gift.GiftPreset;
import com.foxcash.domain.port.in.CreatePixChargeUseCase;
import com.foxcash.domain.port.in.GiftPresetUseCase;
import com.foxcash.domain.port.out.GiftPresetRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Aplicacao: catalogo de Gift PIX do lojista (Spec 23). Presets configuráveis
 * (ativar/desativar) e geracao de cobranca pelo preset ou valor personalizado —
 * reusa o {@link CreatePixChargeUseCase} (Spec 19), com triagem antifraude ativa.
 */
@Service
public class GiftPresetService implements GiftPresetUseCase {

    private final GiftPresetRepository giftPresetRepository;
    private final CreatePixChargeUseCase createPixCharge;
    private final PortalProperties portalProperties;

    public GiftPresetService(GiftPresetRepository giftPresetRepository,
                             CreatePixChargeUseCase createPixCharge,
                             PortalProperties portalProperties) {
        this.giftPresetRepository = giftPresetRepository;
        this.createPixCharge = createPixCharge;
        this.portalProperties = portalProperties;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GiftPreset> presets(UUID merchantId) {
        return giftPresetRepository.findByMerchantId(merchantId);
    }

    @Override
    @Transactional
    public void setActive(UUID merchantId, UUID presetId, boolean active) {
        GiftPreset preset = giftPresetRepository.findByIdAndMerchantId(presetId, merchantId)
                .orElseThrow(() -> new GiftPresetNotFoundException(presetId));
        preset.setActive(active);
        giftPresetRepository.save(preset);
    }

    @Override
    @Transactional
    public CreatePixChargeUseCase.Result giftCharge(UUID merchantId, GiftChargeCommand command) {
        Money amount = resolveAmount(merchantId, command);
        return createPixCharge.create(new CreatePixChargeUseCase.Command(
                merchantId, amount, command.expirationSeconds(), command.buyerId(), command.description(),
                command.idempotencyKey(), null, null, null));
    }

    private Money resolveAmount(UUID merchantId, GiftChargeCommand command) {
        if (command.presetId() != null && command.amount() != null) {
            // Contrato (Spec 23): presetId XOR amount — nunca ambos (QA 2026-08-04).
            throw new IllegalArgumentException("Informe presetId OU amount, nao ambos");
        }
        if (command.presetId() != null) {
            GiftPreset preset = giftPresetRepository.findByIdAndMerchantId(command.presetId(), merchantId)
                    .orElseThrow(() -> new GiftPresetNotFoundException(command.presetId()));
            if (!preset.active()) {
                throw new FraudRuleViolationException("gift_preset_inactive", "Preset de Gift PIX inativo");
            }
            return preset.amount();
        }
        validateAmount(command.amount());
        return command.amount();
    }

    private void validateAmount(Money amount) {
        if (amount == null || amount.amount().signum() <= 0
                || amount.amount().compareTo(portalProperties.getChargeMaxAmount()) > 0) {
            throw new IllegalArgumentException("Valor invalido: deve ser entre 0.01 e "
                    + portalProperties.getChargeMaxAmount());
        }
    }
}
