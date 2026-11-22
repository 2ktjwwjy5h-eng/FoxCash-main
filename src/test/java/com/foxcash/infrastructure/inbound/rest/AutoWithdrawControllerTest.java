package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.AutoWithdrawConfigService;
import com.foxcash.domain.exception.DestinationInactiveException;
import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.infrastructure.inbound.rest.dto.AutoWithdrawConfigRequest;
import com.foxcash.infrastructure.inbound.rest.dto.AutoWithdrawConfigResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST da config de saque automatico (Spec 70): o
 * merchantId vem SEMPRE do principal (nunca do body — impossivel IDOR), o GET
 * nunca devolve 404 e erros de dominio (400/404/409) propagam para o
 * {@link GlobalExceptionHandler}.
 */
class AutoWithdrawControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID DESTINATION_ID = UUID.randomUUID();

    private final AutoWithdrawConfigService service = mock(AutoWithdrawConfigService.class);
    private final Authentication authentication = mock(Authentication.class);

    private AutoWithdrawController controller;

    @BeforeEach
    void setUp() {
        controller = new AutoWithdrawController(service);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    @Test
    void getReturnsDefaultsWhenAbsent() {
        when(service.get(MERCHANT_ID)).thenReturn(
                new AutoWithdrawConfigService.ConfigView(false, Money.of("1000.00"), null));

        AutoWithdrawConfigResponse response = controller.get(authentication);

        assertThat(response.enabled()).isFalse();
        assertThat(response.threshold()).isEqualByComparingTo("1000.00");
        assertThat(response.destinationId()).isNull();
    }

    @Test
    void getReflectsSavedConfig() {
        when(service.get(MERCHANT_ID)).thenReturn(
                new AutoWithdrawConfigService.ConfigView(true, Money.of("500.00"), DESTINATION_ID));

        AutoWithdrawConfigResponse response = controller.get(authentication);

        assertThat(response.enabled()).isTrue();
        assertThat(response.threshold()).isEqualByComparingTo("500.00");
        assertThat(response.destinationId()).isEqualTo(DESTINATION_ID);
    }

    @Test
    void updateDelegatesWithPrincipalMerchantIdAndReturns200() {
        when(service.update(MERCHANT_ID, true, new BigDecimal("1000.00"), DESTINATION_ID))
                .thenReturn(new AutoWithdrawConfigService.ConfigView(true, Money.of("1000.00"), DESTINATION_ID));

        AutoWithdrawConfigResponse response = controller.update(authentication,
                new AutoWithdrawConfigRequest(true, new BigDecimal("1000.00"), DESTINATION_ID));

        assertThat(response.enabled()).isTrue();
        assertThat(response.threshold()).isEqualByComparingTo("1000.00");
        assertThat(response.destinationId()).isEqualTo(DESTINATION_ID);
        // IDOR impossivel: o merchantId usado e o do principal, nunca do body.
        verify(service).update(MERCHANT_ID, true, new BigDecimal("1000.00"), DESTINATION_ID);
    }

    @Test
    void updatePropagatesInvalidThreshold() {
        when(service.update(MERCHANT_ID, false, new BigDecimal("0.00"), null))
                .thenThrow(new IllegalArgumentException("threshold deve ser maior que zero"));

        assertThatThrownBy(() -> controller.update(authentication,
                new AutoWithdrawConfigRequest(false, new BigDecimal("0.00"), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatePropagatesDestinationNotFound() {
        when(service.update(MERCHANT_ID, true, new BigDecimal("100.00"), DESTINATION_ID))
                .thenThrow(new DestinationNotFoundException(DESTINATION_ID));

        assertThatThrownBy(() -> controller.update(authentication,
                new AutoWithdrawConfigRequest(true, new BigDecimal("100.00"), DESTINATION_ID)))
                .isInstanceOf(DestinationNotFoundException.class);
    }

    @Test
    void updatePropagatesDestinationInactive() {
        when(service.update(MERCHANT_ID, true, new BigDecimal("100.00"), DESTINATION_ID))
                .thenThrow(new DestinationInactiveException(DESTINATION_ID));

        assertThatThrownBy(() -> controller.update(authentication,
                new AutoWithdrawConfigRequest(true, new BigDecimal("100.00"), DESTINATION_ID)))
                .isInstanceOf(DestinationInactiveException.class);
    }
}
