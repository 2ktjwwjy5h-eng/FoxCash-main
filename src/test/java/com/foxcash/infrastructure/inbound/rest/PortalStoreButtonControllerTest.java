package com.foxcash.infrastructure.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.StoreButtonPayloadCodec;
import com.foxcash.domain.exception.StoreButtonNotFoundException;
import com.foxcash.domain.exception.TooManyButtonsException;
import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;
import com.foxcash.domain.port.in.StoreButtonManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ReorderButtonsRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreButtonRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreButtonResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST dos botões do menu do portal (Spec 50): o merchantId
 * vem do principal da sessao (ROLE_MERCHANT_PORTAL), POST cria (201), PATCH
 * atualiza, DELETE 204, PUT /order reordena; 404/409 de dominio propagam.
 */
class PortalStoreButtonControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private final StoreButtonManagementUseCase buttons = mock(StoreButtonManagementUseCase.class);
    private final StoreButtonPayloadCodec payloadCodec = new StoreButtonPayloadCodec(new ObjectMapper());
    private final Authentication authentication = mock(Authentication.class);

    private PortalStoreButtonController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalStoreButtonController(buttons, payloadCodec, new ObjectMapper());
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private StoreButton button(String label, StoreButtonAction action, String payload, int position) {
        return StoreButton.create(STORE_ID, label, action, payload, position, NOW);
    }

    @Test
    void listReturnsButtonsOfPrincipal() {
        StoreButton b0 = button("Comprar", StoreButtonAction.BUY, null, 0);
        StoreButton b1 = button("Instagram", StoreButtonAction.SOCIAL, "{\"url\":\"https://ig.com/x\"}", 1);
        when(buttons.list(MERCHANT_ID)).thenReturn(List.of(b0, b1));

        List<StoreButtonResponse> response = controller.list(authentication);

        assertThat(response).hasSize(2);
        assertThat(response.get(0).label()).isEqualTo("Comprar");
        assertThat(response.get(0).action()).isEqualTo(StoreButtonAction.BUY);
        assertThat(response.get(1).payload()).containsEntry("url", "https://ig.com/x");
    }

    @Test
    void getReturnsButtonOfPrincipal() {
        StoreButton b0 = button("Comprar", StoreButtonAction.BUY, null, 0);
        when(buttons.get(MERCHANT_ID, b0.id())).thenReturn(b0);

        StoreButtonResponse response = controller.get(authentication, b0.id());

        assertThat(response.id()).isEqualTo(b0.id());
        assertThat(response.label()).isEqualTo("Comprar");
    }

    @Test
    void getWithoutButtonPropagates404() {
        UUID buttonId = UUID.randomUUID();
        when(buttons.get(MERCHANT_ID, buttonId)).thenThrow(new StoreButtonNotFoundException());

        assertThatThrownBy(() -> controller.get(authentication, buttonId))
                .isInstanceOf(StoreButtonNotFoundException.class);
    }

    @Test
    void createReturns201WithBody() {
        when(buttons.create(any(), any())).thenAnswer(inv -> {
            StoreButtonManagementUseCase.SaveButtonCommand cmd = inv.getArgument(1);
            return button(cmd.label(), cmd.action(), cmd.payloadJson(), 0);
        });

        ResponseEntity<StoreButtonResponse> response = controller.create(authentication,
                new StoreButtonRequest("Comprar", "BUY", null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().label()).isEqualTo("Comprar");
        assertThat(response.getBody().action()).isEqualTo(StoreButtonAction.BUY);
        verify(buttons).create(any(), any());
    }

    @Test
    void createOverLimitPropagates409() {
        when(buttons.create(any(), any())).thenThrow(new TooManyButtonsException());

        assertThatThrownBy(() -> controller.create(authentication,
                new StoreButtonRequest("X", "BUY", null, null)))
                .isInstanceOf(TooManyButtonsException.class);
    }

    @Test
    void createRejectsInvalidAction() {
        assertThatThrownBy(() -> controller.create(authentication,
                new StoreButtonRequest("X", "NAO_EXISTE", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateReturns200WithBody() {
        when(buttons.update(any(), any(), any())).thenAnswer(inv -> {
            StoreButtonManagementUseCase.SaveButtonCommand cmd = inv.getArgument(2);
            return button(cmd.label(), cmd.action(), cmd.payloadJson(), 0);
        });

        StoreButtonResponse response = controller.update(authentication, UUID.randomUUID(),
                new StoreButtonRequest("Instagram", "SOCIAL", Map.of("url", "https://ig.com/x"), null));

        assertThat(response.label()).isEqualTo("Instagram");
        assertThat(response.action()).isEqualTo(StoreButtonAction.SOCIAL);
        verify(buttons).update(any(), any(), any());
    }

    @Test
    void deleteReturns204() {
        ResponseEntity<Void> response = controller.delete(authentication, UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(buttons).delete(any(), any());
    }

    @Test
    void reorderReturns204() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Void> response = controller.reorder(authentication,
                new ReorderButtonsRequest(List.of(id)));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(buttons).reorder(MERCHANT_ID, List.of(id));
    }
}
