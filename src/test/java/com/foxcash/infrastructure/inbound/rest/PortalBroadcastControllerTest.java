package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.BroadcastMediaService;
import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.port.in.BroadcastManagementUseCase;
import com.foxcash.domain.port.in.BroadcastQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastDetailResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastLogResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastRequest;
import com.foxcash.infrastructure.inbound.rest.dto.BroadcastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
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
 * Testes do adaptador REST de broadcasts do portal (Spec 79): o merchantId vem
 * do principal da sessão (ROLE_MERCHANT_PORTAL); POST devolve 201, GET lista/
 * detalha (com logs), DELETE cancela (204); 404/400 de domínio propagam.
 */
class PortalBroadcastControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_BOT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    private final BroadcastManagementUseCase broadcasts = mock(BroadcastManagementUseCase.class);
    private final BroadcastQueryUseCase broadcastQueries = mock(BroadcastQueryUseCase.class);
    private final BroadcastMediaService mediaService = mock(BroadcastMediaService.class);
    private final ProfileScopeResolver scopeResolver = mock(ProfileScopeResolver.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalBroadcastController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalBroadcastController(broadcasts, broadcastQueries, mediaService, scopeResolver);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
        // Sem header → botIds null (lojista inteiro — regressão Spec 124).
        when(scopeResolver.scopeBotIds(any(), any())).thenReturn(null);
    }

    private Broadcast nowBroadcast() {
        return Broadcast.restore(UUID.randomUUID(), STORE_BOT_ID, BroadcastMode.NOW, "-100123456789",
                "Promoção!", null, null, null, null, BroadcastStatus.PENDING, null,
                null, null, NOW, NOW);
    }

    @Test
    void listReturnsBroadcastsOfPrincipal() {
        Broadcast broadcast = nowBroadcast();
        when(broadcastQueries.list(MERCHANT_ID, null, null, null)).thenReturn(List.of(broadcast));

        List<BroadcastResponse> response =
                controller.list(authentication, new MockHttpServletRequest(), null, null);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(broadcast.id());
        assertThat(response.get(0).mode()).isEqualTo(BroadcastMode.NOW);
        assertThat(response.get(0).status()).isEqualTo(BroadcastStatus.PENDING);
    }

    @Test
    void listPropagatesModeAndStatusFilters() {
        controller.list(authentication, new MockHttpServletRequest(), "scheduled", "completed");
        verify(broadcastQueries).list(MERCHANT_ID, null, BroadcastMode.SCHEDULED, BroadcastStatus.COMPLETED);
    }

    @Test
    void createReturns201() {
        Broadcast broadcast = nowBroadcast();
        when(broadcasts.create(eq(MERCHANT_ID), any())).thenReturn(broadcast);

        ResponseEntity<BroadcastResponse> response = controller.create(authentication,
                new BroadcastRequest(STORE_BOT_ID, "NOW", "-100123456789", "Promoção!",
                        null, null, null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().id()).isEqualTo(broadcast.id());
        verify(broadcasts).create(eq(MERCHANT_ID), any());
    }

    @Test
    void getReturnsDetailWithLogs() {
        Broadcast broadcast = nowBroadcast();
        when(broadcastQueries.get(MERCHANT_ID, broadcast.id())).thenReturn(broadcast);
        when(broadcastQueries.logs(MERCHANT_ID, broadcast.id())).thenReturn(List.of());

        BroadcastDetailResponse response = controller.get(authentication, broadcast.id());

        assertThat(response.id()).isEqualTo(broadcast.id());
        assertThat(response.content()).isEqualTo("Promoção!");
        assertThat(response.logs()).isEmpty();
    }

    @Test
    void getWithoutBroadcastPropagates404() {
        UUID id = UUID.randomUUID();
        when(broadcastQueries.get(MERCHANT_ID, id)).thenThrow(new BroadcastNotFoundException());

        assertThatThrownBy(() -> controller.get(authentication, id))
                .isInstanceOf(BroadcastNotFoundException.class);
    }

    @Test
    void cancelReturns204() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Void> response = controller.cancel(authentication, id);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(broadcasts).cancel(MERCHANT_ID, id);
    }

    @Test
    void getLogsMapsResponse() {
        Broadcast broadcast = nowBroadcast();
        when(broadcastQueries.get(MERCHANT_ID, broadcast.id())).thenReturn(broadcast);
        when(broadcastQueries.logs(MERCHANT_ID, broadcast.id())).thenReturn(
                List.of(com.foxcash.domain.model.broadcast.BroadcastLog.create(
                        broadcast.id(), "12345",
                        com.foxcash.domain.model.broadcast.BroadcastLogStatus.SUCCESS, null, NOW)));

        BroadcastDetailResponse response = controller.get(authentication, broadcast.id());

        assertThat(response.logs()).hasSize(1);
        BroadcastLogResponse log = response.logs().get(0);
        assertThat(log.recipientId()).isEqualTo("12345");
        assertThat(log.status()).isEqualTo(com.foxcash.domain.model.broadcast.BroadcastLogStatus.SUCCESS);
    }
}
