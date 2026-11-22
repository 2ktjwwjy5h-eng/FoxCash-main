package com.foxcash.infrastructure.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.PlanActionPayloadCodec;
import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.DeliveryActionStatus;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.domain.model.delivery.PlanDeliveryStatus;
import com.foxcash.domain.model.plan.DeliveryActionType;
import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.port.in.DeliveryAccessRecordingUseCase;
import com.foxcash.domain.port.in.DeliveryActionCompletionUseCase;
import com.foxcash.domain.port.in.DeliveryQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.DeliveryAccessRequest;
import com.foxcash.infrastructure.inbound.rest.dto.DeliveryCompleteRequest;
import com.foxcash.infrastructure.inbound.rest.dto.DeliveryResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST de entregas (Spec 49): pending com ações + payload
 * decifrado, by-transaction, complete 204, retry e registro de acesso
 * (Spec 49+).
 */
class FoxDeliveryControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    private final DeliveryQueryUseCase deliveryQuery = mock(DeliveryQueryUseCase.class);
    private final DeliveryActionCompletionUseCase completion = mock(DeliveryActionCompletionUseCase.class);
    private final DeliveryAccessRecordingUseCase accessRecording = mock(DeliveryAccessRecordingUseCase.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    private final Authentication authentication = mock(Authentication.class);

    private FoxDeliveryController controller;

    @BeforeEach
    void setUp() {
        controller = new FoxDeliveryController(deliveryQuery, completion, accessRecording,
                new PlanActionPayloadCodec(new ObjectMapper()), clientIpResolver);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
        when(deliveryQuery.accessLogs(MERCHANT_ID, DELIVERY_ID)).thenReturn(List.of());
    }

    private PlanDelivery delivery() {
        return PlanDelivery.restore(DELIVERY_ID, UUID.randomUUID(), UUID.randomUUID(), TX_ID,
                Platform.DISCORD, "123456789012345678", "buyer-hash", PlanDeliveryStatus.PENDING,
                NOW, null);
    }

    @Test
    void pendingReturnsDeliveriesWithDecodedPayloads() {
        DeliveryActionExec exec = DeliveryActionExec.restore(UUID.randomUUID(), DELIVERY_ID, 0,
                DeliveryActionType.SEND_LINK, "{\"url\":\"https://t.me/+abc\"}",
                DeliveryActionStatus.PENDING, null, null);
        when(deliveryQuery.pending(MERCHANT_ID)).thenReturn(List.of(delivery()));
        when(deliveryQuery.actions(MERCHANT_ID, DELIVERY_ID)).thenReturn(List.of(exec));

        List<DeliveryResponse> response = controller.pending(authentication);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).platformUserId()).isEqualTo("123456789012345678");
        assertThat(response.get(0).actions()).hasSize(1);
        assertThat(response.get(0).actions().get(0).payload().get("url")).isEqualTo("https://t.me/+abc");
    }

    @Test
    void byTransactionReturnsDelivery() {
        when(deliveryQuery.byTransaction(MERCHANT_ID, TX_ID)).thenReturn(delivery());
        when(deliveryQuery.actions(MERCHANT_ID, DELIVERY_ID)).thenReturn(List.of());

        DeliveryResponse response = controller.byTransaction(authentication, TX_ID);

        assertThat(response.transactionId()).isEqualTo(TX_ID);
    }

    @Test
    void completeReturns204AndDelegates() {
        ResponseEntity<Void> response = controller.complete(authentication, DELIVERY_ID, 0,
                new DeliveryCompleteRequest(DeliveryActionStatus.DONE, null));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(completion).complete(eq(MERCHANT_ID), eq(DELIVERY_ID), eq(0),
                eq(DeliveryActionStatus.DONE), eq(null));
    }

    @Test
    void completeWithoutStatusThrows400() {
        assertThatThrownBy(() -> controller.complete(authentication, DELIVERY_ID, 0,
                new DeliveryCompleteRequest(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(completion, org.mockito.Mockito.never())
                .complete(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void retryReturnsDelivery() {
        when(completion.retry(MERCHANT_ID, DELIVERY_ID)).thenReturn(delivery());
        when(deliveryQuery.actions(MERCHANT_ID, DELIVERY_ID)).thenReturn(List.of());

        DeliveryResponse response = controller.retry(authentication, DELIVERY_ID);

        assertThat(response.id()).isEqualTo(DELIVERY_ID);
    }

    @Test
    void recordAccessReturns204AndDelegatesWithCapturedIpAndDevice() {
        when(clientIpResolver.resolve(httpRequest)).thenReturn("200.201.202.203");
        when(httpRequest.getHeader("User-Agent")).thenReturn("FoxBot/1.0");

        ResponseEntity<Void> response = controller.recordAccess(authentication, DELIVERY_ID,
                new DeliveryAccessRequest("acessou o link"), httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(accessRecording).record(MERCHANT_ID, DELIVERY_ID, "200.201.202.203", "FoxBot/1.0",
                "acessou o link");
    }

    @Test
    void recordAccessWithoutBodyDelegatesWithNullNote() {
        when(clientIpResolver.resolve(httpRequest)).thenReturn("200.201.202.203");
        when(httpRequest.getHeader("User-Agent")).thenReturn(null);

        ResponseEntity<Void> response = controller.recordAccess(authentication, DELIVERY_ID,
                null, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(accessRecording).record(MERCHANT_ID, DELIVERY_ID, "200.201.202.203", null, null);
    }
}
