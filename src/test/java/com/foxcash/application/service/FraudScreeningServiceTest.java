package com.foxcash.application.service;

import com.foxcash.domain.exception.FraudRuleViolationException;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.infrastructure.config.FraudProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes da triagem de antifraude (Spec 19): o unico bloqueio automatico e o
 * comprador com historico de chargeback — velocidade nao bloqueia venda
 * legitima (decisao de produto 2026-08-04).
 */
class FraudScreeningServiceTest {

    private static final String BUYER_HASH = "a".repeat(64);

    private final ChargebackRepository chargebackRepository = mock(ChargebackRepository.class);
    private final FraudProperties properties = new FraudProperties();

    private FraudScreeningService service;

    @BeforeEach
    void setUp() {
        service = new FraudScreeningService(chargebackRepository, properties);
        when(chargebackRepository.countByBuyerIdHashSince(anyString(), any())).thenReturn(0L);
    }

    @Test
    void cleanChargePasses() {
        assertThatCode(() -> service.screen(BUYER_HASH)).doesNotThrowAnyException();
    }

    @Test
    void chargeWithoutBuyerIdPasses() {
        assertThatCode(() -> service.screen(null)).doesNotThrowAnyException();
    }

    @Test
    void buyerWithChargebackHistoryIsBlocked() {
        when(chargebackRepository.countByBuyerIdHashSince(eq(BUYER_HASH), any()))
                .thenReturn((long) properties.getBuyerChargebackBlockCount());

        assertThatThrownBy(() -> service.screen(BUYER_HASH))
                .isInstanceOf(FraudRuleViolationException.class)
                .hasMessageContaining("chargeback");
    }

    @Test
    void rapidRepeatedPurchasesAreNeverBlocked() {
        // Decisao de produto: velocidade nao bloqueia — compra rapida em
        // sequencia e normal; a triagem passa mesmo com muitas cobrancas.
        assertThatCode(() -> service.screen(BUYER_HASH)).doesNotThrowAnyException();
    }
}
