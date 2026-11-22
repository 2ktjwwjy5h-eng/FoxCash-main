package com.foxcash.application.service;

import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.exception.GiftPresetNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.gift.GiftPreset;
import com.foxcash.domain.port.in.CreatePixChargeUseCase;
import com.foxcash.domain.port.in.GiftPresetUseCase;
import com.foxcash.domain.port.out.GiftPresetRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do catalogo de Gift PIX (Spec 23): presets configuraveis, cobranca por
 * preset (ativo/inativo) e valor personalizado com limite.
 */
class GiftPresetServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PRESET_ID = UUID.randomUUID();

    private final GiftPresetRepository giftPresetRepository = mock(GiftPresetRepository.class);
    private final CreatePixChargeUseCase createPixCharge = mock(CreatePixChargeUseCase.class);
    private final PortalProperties portalProperties = new PortalProperties();

    private GiftPresetService service;

    @BeforeEach
    void setUp() {
        service = new GiftPresetService(giftPresetRepository, createPixCharge, portalProperties);
    }

    private GiftPreset preset(boolean active) {
        return GiftPreset.restore(PRESET_ID, MERCHANT_ID, Money.of("20.00"), "R$ 20", active, NOW);
    }

    @Test
    void presetsReturnsMerchantCatalog() {
        when(giftPresetRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(preset(true), preset(true)));

        assertThat(service.presets(MERCHANT_ID)).hasSize(2);
    }

    @Test
    void setActivePersistsToggle() {
        GiftPreset p = preset(true);
        when(giftPresetRepository.findByIdAndMerchantId(PRESET_ID, MERCHANT_ID)).thenReturn(Optional.of(p));

        service.setActive(MERCHANT_ID, PRESET_ID, false);

        assertThat(p.active()).isFalse();
        verify(giftPresetRepository).save(p);
    }

    @Test
    void setActiveThrowsNotFoundWhenPresetBelongsToAnotherMerchant() {
        when(giftPresetRepository.findByIdAndMerchantId(PRESET_ID, MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setActive(MERCHANT_ID, PRESET_ID, false))
                .isInstanceOf(GiftPresetNotFoundException.class);
    }

    @Test
    void giftChargeWithActivePresetUsesPresetAmount() {
        GiftPreset p = preset(true);
        when(giftPresetRepository.findByIdAndMerchantId(PRESET_ID, MERCHANT_ID)).thenReturn(Optional.of(p));
        when(createPixCharge.create(any())).thenReturn(new CreatePixChargeUseCase.Result(UUID.randomUUID()));

        CreatePixChargeUseCase.Result result = service.giftCharge(MERCHANT_ID,
                new GiftPresetUseCase.GiftChargeCommand(PRESET_ID, null, 3600, null, null, "gift-key-1"));

        // Mutation (NullReturnVals L56): giftCharge devolve o resultado do
        // CreatePixChargeUseCase (não null).
        assertThat(result).isNotNull();
        verify(createPixCharge).create(new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("20.00"), 3600, null, null, "gift-key-1",
                null, null, null));
    }

    @Test
    void giftChargeWithInactivePresetThrowsConflict() {
        GiftPreset p = preset(false);
        when(giftPresetRepository.findByIdAndMerchantId(PRESET_ID, MERCHANT_ID)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.giftCharge(MERCHANT_ID, new GiftPresetUseCase.GiftChargeCommand(
                PRESET_ID, null, 3600, null, null, "gift-key-1")))
                .isInstanceOf(FraudRuleViolationException.class)
                .hasMessageContaining("inativo");
        verify(createPixCharge, never()).create(any());
    }

    @Test
    void giftChargeWithUnknownPresetThrowsNotFound() {
        // Mutation (NullReturnVals L68, lambda do orElseThrow): preset inexistente
        // → GiftPresetNotFoundException (não NPE do `throw null`).
        when(giftPresetRepository.findByIdAndMerchantId(PRESET_ID, MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.giftCharge(MERCHANT_ID,
                new GiftPresetUseCase.GiftChargeCommand(PRESET_ID, null, 3600, null, null, "gift-key-x")))
                .isInstanceOf(GiftPresetNotFoundException.class);
        verify(createPixCharge, never()).create(any());
    }

    @Test
    void giftChargeWithCustomAmountUsesAmount() {
        when(createPixCharge.create(any())).thenReturn(new CreatePixChargeUseCase.Result(UUID.randomUUID()));

        service.giftCharge(MERCHANT_ID, new GiftPresetUseCase.GiftChargeCommand(
                null, Money.of("33.00"), 3600, null, "Gift", "gift-key-2"));

        verify(createPixCharge).create(new CreatePixChargeUseCase.Command(
                MERCHANT_ID, Money.of("33.00"), 3600, null, "Gift", "gift-key-2",
                null, null, null));
    }

    @Test
    void giftChargeRejectsPresetAndAmountTogether() {
        // Contrato (Spec 23): presetId XOR amount — ambos → 400 (QA 2026-08-04).
        assertThatThrownBy(() -> service.giftCharge(MERCHANT_ID,
                new GiftPresetUseCase.GiftChargeCommand(PRESET_ID, Money.of("33.00"), 3600, null, null, "gift-key-1")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(createPixCharge, never()).create(any());
    }

    @Test
    void giftChargeRejectsInvalidAmounts() {
        // Sem preset e sem valor → invalido.
        assertThatThrownBy(() -> service.giftCharge(MERCHANT_ID,
                new GiftPresetUseCase.GiftChargeCommand(null, null, 3600, null, null, "gift-key-1")))
                .isInstanceOf(IllegalArgumentException.class);
        // Valor zero → invalido.
        assertThatThrownBy(() -> service.giftCharge(MERCHANT_ID,
                new GiftPresetUseCase.GiftChargeCommand(null, Money.of("0.00"), 3600, null, null, "gift-key-2")))
                .isInstanceOf(IllegalArgumentException.class);
        // Acima do limite configuravel → invalido.
        portalProperties.setChargeMaxAmount(new BigDecimal("100.00"));
        assertThatThrownBy(() -> service.giftCharge(MERCHANT_ID,
                new GiftPresetUseCase.GiftChargeCommand(null, Money.of("100.01"), 3600, null, null, "gift-key-3")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(createPixCharge, never()).create(any());
    }

    @Test
    void giftChargeWithAmountExactlyAtMaxIsAccepted() {
        // Mutation (ConditionalsBoundary L80: compareTo(max) > 0 → >= 0): valor
        // IGUAL ao teto é aceito (mutante rejeitaria).
        portalProperties.setChargeMaxAmount(new BigDecimal("100.00"));
        when(createPixCharge.create(any())).thenReturn(new CreatePixChargeUseCase.Result(UUID.randomUUID()));

        CreatePixChargeUseCase.Result result = service.giftCharge(MERCHANT_ID,
                new GiftPresetUseCase.GiftChargeCommand(null, Money.of("100.00"), 3600, null, null, "gift-key-max"));

        assertThat(result).isNotNull();
        verify(createPixCharge).create(any());
    }
}
