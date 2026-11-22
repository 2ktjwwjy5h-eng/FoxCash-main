package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastLogStatus;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.port.in.BroadcastDispatchUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastDueResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastLogRequest;
import com.foxcash.infrastructure.inbound.rest.dto.WelcomeTriggerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST da API do bot (Spec 79): o merchantId vem do
 * principal da API key (padrão Spec 51); due devolve as pendências, sent/logs
 * devolvem 204 (idempotentes) e welcome-trigger devolve 200 com a mensagem ou
 * 204 no-op.
 */
class FoxBroadcastControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    private final BroadcastDispatchUseCase dispatch = mock(BroadcastDispatchUseCase.class);
    private final Authentication authentication = mock(Authentication.class);

    private FoxBroadcastController controller;

    @BeforeEach
    void setUp() {
        controller = new FoxBroadcastController(dispatch);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private Broadcast pendingNow() {
        return Broadcast.restore(UUID.randomUUID(), BOT_ID, BroadcastMode.NOW, "-100123456789",
                "Promoção!", null, null, null, null, BroadcastStatus.PENDING, null,
                null, null, NOW, NOW);
    }

    @Test
    void dueReturnsPendingBroadcasts() {
        Broadcast broadcast = pendingNow();
        when(dispatch.due(MERCHANT_ID, NOW.plusSeconds(60))).thenReturn(List.of(broadcast));

        List<BroadcastDueResponse> response = controller.due(authentication, "2026-08-12T12:01:00Z");

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(broadcast.id());
        assertThat(response.get(0).content()).isEqualTo("Promoção!");
    }

    @Test
    void markSentReturns204() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Void> response = controller.markSent(authentication, id);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(dispatch).markSent(MERCHANT_ID, id);
    }

    @Test
    void reportLogReturns204() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Void> response = controller.reportLog(authentication, id,
                new BroadcastLogRequest("12345", "SUCCESS", null));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(dispatch).reportLog(MERCHANT_ID, id, "12345", BroadcastLogStatus.SUCCESS, null);
    }

    @Test
    void welcomeTriggerReturns200WithMessage() {
        when(dispatch.welcomeTrigger(eq(MERCHANT_ID), eq(BOT_ID), eq("-1001"), eq("user42")))
                .thenReturn(new BroadcastDispatchUseCase.WelcomeResult(
                        UUID.randomUUID(), "Bem-vindo!", null, null, null, null));

        ResponseEntity<?> response = controller.welcomeTrigger(authentication,
                new WelcomeTriggerRequest(BOT_ID, "-1001", "user42"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        BroadcastDispatchUseCase.WelcomeResult body =
                (BroadcastDispatchUseCase.WelcomeResult) response.getBody();
        assertThat(body.content()).isEqualTo("Bem-vindo!");
    }

    @Test
    void welcomeTriggerReturns204NoOpWhenNoMessage() {
        when(dispatch.welcomeTrigger(any(), any(), any(), any())).thenReturn(null);

        ResponseEntity<?> response = controller.welcomeTrigger(authentication,
                new WelcomeTriggerRequest(BOT_ID, "-1001", "user42"));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }
}
