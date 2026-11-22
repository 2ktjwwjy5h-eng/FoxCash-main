package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.exception.GiftPresetNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.gift.GiftPreset;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.CreatePixChargeUseCase;
import com.foxcash.domain.port.in.GiftPresetUseCase;
import com.foxcash.domain.port.in.QueryTransactionUseCase;
import com.foxcash.infrastructure.config.PortalProperties;
import com.foxcash.infrastructure.inbound.rest.dto.GiftPresetResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GiftPresetUpdateRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PixChargeResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PortalChargeRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PortalGiftChargeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST do gerador de cobrança do portal (Spec 23): o
 * merchantId vem do principal da sessão (ROLE_MERCHANT_PORTAL), validações de
 * valor e mapeamento das respostas. (QA 2026-08-04 — cobertura da camada REST.)
 */
class MerchantChargeControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PRESET_ID = UUID.randomUUID();
    private static final UUID TRANSACTION_ID = UUID.randomUUID();

    private final CreatePixChargeUseCase createPixCharge = mock(CreatePixChargeUseCase.class);
    private final QueryTransactionUseCase queryTransaction = mock(QueryTransactionUseCase.class);
    private final GiftPresetUseCase giftPresets = mock(GiftPresetUseCase.class);
    private final PortalProperties portalProperties = new PortalProperties();
    private final ProfileScopeResolver scopeResolver = mock(ProfileScopeResolver.class);
    private final Authentication authentication = mock(Authentication.class);

    private MerchantChargeController controller;

    @BeforeEach
    void setUp() {
        controller = new MerchantChargeController(createPixCharge, queryTransaction, giftPresets,
                portalProperties, scopeResolver);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
        when(createPixCharge.create(any())).thenReturn(new CreatePixChargeUseCase.Result(TRANSACTION_ID));
        when(queryTransaction.findById(TRANSACTION_ID)).thenReturn(Optional.of(transaction()));
    }

    private PixTransaction transaction() {
        return PixTransaction.restore(TRANSACTION_ID, MERCHANT_ID, Money.of("10.00"), "Gift",
                null, "EREF123", "EREF123", "qrcode", "000201...", TransactionStatus.PENDING,
                Instant.now(), Instant.now(), null, null);
    }

    private GiftPreset preset() {
        return GiftPreset.restore(PRESET_ID, MERCHANT_ID, Money.of("20.00"), "R$ 20", true, Instant.now());
    }

    @Test
    void createReturns201WithPixChargeResponse() {
        ResponseEntity<PixChargeResponse> response = controller.create(authentication,
                new MockHttpServletRequest(),
                new PortalChargeRequest(new BigDecimal("99.90"), "Cobranca", 3600, null, "cob-key-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().transactionId()).isEqualTo(TRANSACTION_ID);
        // O merchantId vem do principal da sessão — nunca do body.
        verify(createPixCharge).create(any());
    }

    @Test
    void createRejectsNullAmount() {
        assertThatThrownBy(() -> controller.create(authentication, new MockHttpServletRequest(),
                new PortalChargeRequest(null, "Cobranca", 3600, null, "cob-key-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsAmountAboveChargeMax() {
        portalProperties.setChargeMaxAmount(new BigDecimal("100.00"));

        assertThatThrownBy(() -> controller.create(authentication, new MockHttpServletRequest(),
                new PortalChargeRequest(new BigDecimal("100.01"), "Cobranca", 3600, null, "cob-key-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void giftPresetsReturnsMappedCatalog() {
        when(giftPresets.presets(MERCHANT_ID)).thenReturn(List.of(preset()));

        List<GiftPresetResponse> response = controller.giftPresets(authentication);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(PRESET_ID);
        assertThat(response.get(0).amount()).isEqualByComparingTo("20.00");
        assertThat(response.get(0).active()).isTrue();
    }

    @Test
    void updatePresetReturnsNoContent() {
        ResponseEntity<Void> response = controller.updatePreset(authentication, PRESET_ID,
                new GiftPresetUpdateRequest(false));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(giftPresets).setActive(MERCHANT_ID, PRESET_ID, false);
    }

    @Test
    void updatePresetNotFoundPropagates404() {
        doThrow(new GiftPresetNotFoundException(PRESET_ID))
                .when(giftPresets).setActive(MERCHANT_ID, PRESET_ID, true);

        assertThatThrownBy(() -> controller.updatePreset(authentication, PRESET_ID,
                new GiftPresetUpdateRequest(true)))
                .isInstanceOf(GiftPresetNotFoundException.class);
    }

    @Test
    void giftWithPresetReturns201() {
        when(giftPresets.giftCharge(any(), any())).thenReturn(new CreatePixChargeUseCase.Result(TRANSACTION_ID));

        ResponseEntity<PixChargeResponse> response = controller.gift(authentication,
                new PortalGiftChargeRequest(PRESET_ID, null, 3600, null, null, "gift-key-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().transactionId()).isEqualTo(TRANSACTION_ID);
    }

    @Test
    void giftWithInactivePresetPropagatesConflict() {
        when(giftPresets.giftCharge(any(), any()))
                .thenThrow(new FraudRuleViolationException("gift_preset_inactive", "Preset de Gift PIX inativo"));

        assertThatThrownBy(() -> controller.gift(authentication,
                new PortalGiftChargeRequest(PRESET_ID, null, 3600, null, null, "gift-key-1")))
                .isInstanceOf(FraudRuleViolationException.class);
    }
}
