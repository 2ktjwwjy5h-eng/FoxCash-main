package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.PlanInactiveException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.in.PlanPurchaseUseCase;
import com.foxcash.domain.port.in.QueryTransactionUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.PlanPurchaseRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PlanPurchaseResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST de compra de plano (Spec 49): 201 com o contrato do
 * pix/cob + planId; platform inválida → 400; plano inativo propaga 409; e
 * provas de entrega (Spec 49+) — termos aceitos + IP/User-Agent capturados.
 */
class FoxPlanControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private final PlanPurchaseUseCase planPurchase = mock(PlanPurchaseUseCase.class);
    private final QueryTransactionUseCase queryTransaction = mock(QueryTransactionUseCase.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    private final Authentication authentication = mock(Authentication.class);

    private FoxPlanController controller;

    @BeforeEach
    void setUp() {
        controller = new FoxPlanController(planPurchase, queryTransaction, clientIpResolver);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private PlanPurchaseRequest request(String platform) {
        return new PlanPurchaseRequest(platform, "user-1", null, 3600, "key-1", true, null);
    }

    private PixTransaction transaction() {
        return PixTransaction.restore(TX_ID, MERCHANT_ID, Money.of("39.90"), "VIP",
                null, "E123", "E123", "qr", "copia-e-cola", TransactionStatus.PENDING,
                Instant.parse("2026-08-05T12:00:00Z"), Instant.parse("2026-08-05T12:00:00Z"), null,
                null, "key-1", PLAN_ID, Platform.DISCORD, "user-1");
    }

    @Test
    void purchaseReturns201WithPlanId() {
        when(planPurchase.purchase(any(), any(), any())).thenReturn(new PlanPurchaseUseCase.Result(TX_ID));
        when(queryTransaction.findById(TX_ID)).thenReturn(Optional.of(transaction()));

        ResponseEntity<PlanPurchaseResponse> response = controller.purchase(authentication, PLAN_ID,
                request("DISCORD"), httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().transactionId()).isEqualTo(TX_ID);
        assertThat(response.getBody().planId()).isEqualTo(PLAN_ID);
        assertThat(response.getBody().pixQrCode()).isEqualTo("qr");
    }

    @Test
    void purchaseWithInvalidPlatformThrows400() {
        assertThatThrownBy(() -> controller.purchase(authentication, PLAN_ID,
                new PlanPurchaseRequest("SNAPCHAT", "user-1", null, 3600, "key-1", true, null), httpRequest))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void purchaseOfInactivePlanPropagates409() {
        when(planPurchase.purchase(any(), any(), any())).thenThrow(new PlanInactiveException());

        assertThatThrownBy(() -> controller.purchase(authentication, PLAN_ID,
                new PlanPurchaseRequest("TELEGRAM", "user-1", null, 3600, "key-1", true, null), httpRequest))
                .isInstanceOf(PlanInactiveException.class);
    }

    @Test
    void purchaseMissingTransactionThrows404() {
        when(planPurchase.purchase(any(), any(), any())).thenReturn(new PlanPurchaseUseCase.Result(TX_ID));
        when(queryTransaction.findById(TX_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.purchase(authentication, PLAN_ID,
                request("DISCORD"), httpRequest))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void purchaseCapturesTermsIpAndDeviceWhenAccepted() {
        when(clientIpResolver.resolve(httpRequest)).thenReturn("200.201.202.203");
        when(httpRequest.getHeader("User-Agent")).thenReturn("FoxBot/1.0");
        when(planPurchase.purchase(any(), any(), any())).thenReturn(new PlanPurchaseUseCase.Result(TX_ID));
        when(queryTransaction.findById(TX_ID)).thenReturn(Optional.of(transaction()));

        controller.purchase(authentication, PLAN_ID, request("DISCORD"), httpRequest);

        verify(planPurchase).purchase(eq(MERCHANT_ID), eq(PLAN_ID),
                org.mockito.ArgumentMatchers.argThat(cmd ->
                        cmd.termsAcceptedAt() != null
                                && "200.201.202.203".equals(cmd.buyerIp())
                                && "FoxBot/1.0".equals(cmd.buyerDevice())));
    }

    @Test
    void purchaseWithoutTermsAcceptanceLeavesTermsNull() {
        when(clientIpResolver.resolve(httpRequest)).thenReturn("200.201.202.203");
        when(httpRequest.getHeader("User-Agent")).thenReturn(null);
        when(planPurchase.purchase(any(), any(), any())).thenReturn(new PlanPurchaseUseCase.Result(TX_ID));
        when(queryTransaction.findById(TX_ID)).thenReturn(Optional.of(transaction()));

        controller.purchase(authentication, PLAN_ID,
                new PlanPurchaseRequest("DISCORD", "user-1", null, 3600, "key-1", false, null), httpRequest);

        verify(planPurchase).purchase(eq(MERCHANT_ID), eq(PLAN_ID),
                org.mockito.ArgumentMatchers.argThat(cmd ->
                        cmd.termsAcceptedAt() == null
                                && "200.201.202.203".equals(cmd.buyerIp())
                                && cmd.buyerDevice() == null));
    }
}
