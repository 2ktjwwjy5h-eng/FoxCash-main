package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.PixCodeParseException;
import com.foxcash.domain.exception.PvsAmountException;
import com.foxcash.domain.exception.PvsLimitExceededException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.in.PvsPaymentUseCase;
import com.foxcash.domain.pvs.TestPixCodes;
import com.foxcash.infrastructure.inbound.rest.dto.PvsLimitsResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PvsPayRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PvsPayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST do Pagamento via Saldo (Spec 120): o merchantId vem
 * SEMPRE do principal (nunca do body — impossivel IDOR), o shape mínimo do
 * {@code pixCode} é validado e os erros de domínio (400/409) propagam com os
 * códigos específicos para o {@link GlobalExceptionHandler}.
 */
class PvsControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String PIX_CODE = TestPixCodes.staticEmv("52998224725", null);

    private final PvsPaymentUseCase useCase = mock(PvsPaymentUseCase.class);
    private final Authentication authentication = mock(Authentication.class);

    private PvsController controller;

    @BeforeEach
    void setUp() {
        controller = new PvsController(useCase);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    @Test
    void payDelegatesWithPrincipalMerchantIdAndReturns200() {
        UUID withdrawalId = UUID.randomUUID();
        when(useCase.pay(any())).thenReturn(new PvsPaymentUseCase.Result(
                withdrawalId, Money.of("40.00"), Money.of("2.50"), Money.of("60.00"), "PROCESSING"));

        PvsPayResponse response = controller.pay(authentication,
                new PvsPayRequest(PIX_CODE, new BigDecimal("40.00"))).getBody();

        assertThat(response.withdrawalId()).isEqualTo(withdrawalId);
        assertThat(response.amount()).isEqualByComparingTo("40.00");
        assertThat(response.fee()).isEqualByComparingTo("2.50");
        assertThat(response.walletBalance()).isEqualByComparingTo("60.00");
        assertThat(response.status()).isEqualTo("PROCESSING");

        // IDOR impossivel: o merchantId usado é o do principal, nunca do body.
        verify(useCase).pay(new PvsPaymentUseCase.PayCommand(MERCHANT_ID, PIX_CODE, Money.of("40.00")));
    }

    @Test
    void payPassesNullAmountWhenNotProvided() {
        when(useCase.pay(any())).thenReturn(new PvsPaymentUseCase.Result(
                UUID.randomUUID(), Money.of("40.00"), Money.of("2.50"), Money.of("60.00"), "PROCESSING"));

        controller.pay(authentication, new PvsPayRequest(PIX_CODE, null));

        verify(useCase).pay(new PvsPaymentUseCase.PayCommand(MERCHANT_ID, PIX_CODE, null));
    }

    @Test
    void payPropagatesPixCodeParseError() {
        when(useCase.pay(any())).thenThrow(new PixCodeParseException("pvs_dynamic_pix_not_supported", "dinâmico"));

        assertThatThrownBy(() -> controller.pay(authentication, new PvsPayRequest(PIX_CODE, null)))
                .isInstanceOf(PixCodeParseException.class)
                .satisfies(e -> assertThat(((PixCodeParseException) e).code())
                        .isEqualTo("pvs_dynamic_pix_not_supported"));
    }

    @Test
    void payPropagatesAmountError() {
        when(useCase.pay(any())).thenThrow(new PvsAmountException("pvs_amount_mismatch", "divergente"));

        assertThatThrownBy(() -> controller.pay(authentication, new PvsPayRequest(PIX_CODE, new BigDecimal("20.00"))))
                .isInstanceOf(PvsAmountException.class)
                .satisfies(e -> assertThat(((PvsAmountException) e).code()).isEqualTo("pvs_amount_mismatch"));
    }

    @Test
    void payPropagatesLimitError() {
        when(useCase.pay(any())).thenThrow(new PvsLimitExceededException("pvs_daily_cap_exceeded", "teto"));

        assertThatThrownBy(() -> controller.pay(authentication, new PvsPayRequest(PIX_CODE, new BigDecimal("20.00"))))
                .isInstanceOf(PvsLimitExceededException.class)
                .satisfies(e -> assertThat(((PvsLimitExceededException) e).code()).isEqualTo("pvs_daily_cap_exceeded"));
    }

    @Test
    void limitsReturnsUsedAndRemaining() {
        when(useCase.limits(MERCHANT_ID)).thenReturn(new PvsPaymentUseCase.Limits(
                Money.of("5000.00"), Money.of("1234.50"), Money.of("3765.50"), Money.of("1000.00")));

        PvsLimitsResponse response = controller.limits(authentication);

        assertThat(response.dailyCap()).isEqualByComparingTo("5000.00");
        assertThat(response.usedToday()).isEqualByComparingTo("1234.50");
        assertThat(response.remaining()).isEqualByComparingTo("3765.50");
        assertThat(response.maxPerPayment()).isEqualByComparingTo("1000.00");
        verify(useCase).limits(MERCHANT_ID);
    }
}
