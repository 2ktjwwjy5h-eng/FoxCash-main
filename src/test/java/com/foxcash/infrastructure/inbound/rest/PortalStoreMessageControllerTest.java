package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.StoreMessageNotFoundException;
import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageAudience;
import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;
import com.foxcash.domain.port.in.StoreMessageManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ScheduleMessageRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ScheduledMessageResponse;
import com.foxcash.infrastructure.inbound.rest.dto.StoreMessageRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreMessageResponse;
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
 * Testes do adaptador REST das mensagens da loja do portal (Spec 51): o
 * merchantId vem do principal da sessao (ROLE_MERCHANT_PORTAL), POST upsert
 * devolve 201 na criação/200 na atualização (1 por event), PATCH/DELETE,
 * POST /schedule cria agendamento (201); 404/400 de domínio propagam.
 */
class PortalStoreMessageControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private final StoreMessageManagementUseCase messages = mock(StoreMessageManagementUseCase.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalStoreMessageController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalStoreMessageController(messages);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    @Test
    void listReturnsMessagesOfPrincipal() {
        StoreMessage welcome = StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME, "Oi!", NOW);
        when(messages.list(MERCHANT_ID)).thenReturn(List.of(welcome));

        List<StoreMessageResponse> response = controller.list(authentication);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).event()).isEqualTo(StoreMessageEvent.WELCOME);
        assertThat(response.get(0).text()).isEqualTo("Oi!");
    }

    @Test
    void getReturnsMessageOfPrincipal() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.FAQ, "Dúvidas?", NOW);
        when(messages.get(MERCHANT_ID, message.id())).thenReturn(message);

        StoreMessageResponse response = controller.get(authentication, message.id());

        assertThat(response.id()).isEqualTo(message.id());
    }

    @Test
    void getWithoutMessagePropagates404() {
        UUID messageId = UUID.randomUUID();
        when(messages.get(MERCHANT_ID, messageId)).thenThrow(new StoreMessageNotFoundException());

        assertThatThrownBy(() -> controller.get(authentication, messageId))
                .isInstanceOf(StoreMessageNotFoundException.class);
    }

    @Test
    void upsertReturns201OnCreate() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME, "Oi!", NOW);
        when(messages.upsert(eq(MERCHANT_ID), any()))
                .thenReturn(new StoreMessageManagementUseCase.UpsertResult(message, true));

        ResponseEntity<StoreMessageResponse> response = controller.upsert(authentication,
                new StoreMessageRequest("welcome", "Oi!", null));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().event()).isEqualTo(StoreMessageEvent.WELCOME);
        verify(messages).upsert(eq(MERCHANT_ID), any());
    }

    @Test
    void upsertReturns200OnUpdate() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME, "Oi!", NOW);
        when(messages.upsert(eq(MERCHANT_ID), any()))
                .thenReturn(new StoreMessageManagementUseCase.UpsertResult(message, false));

        ResponseEntity<StoreMessageResponse> response = controller.upsert(authentication,
                new StoreMessageRequest("WELCOME", "Oi!", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void upsertRejectsInvalidEvent() {
        assertThatThrownBy(() -> controller.upsert(authentication,
                new StoreMessageRequest("NAO_EXISTE", "Oi!", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateReturns200WithBody() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.PROMO, "Promo!", NOW);
        when(messages.update(eq(MERCHANT_ID), eq(message.id()), any())).thenReturn(message);

        StoreMessageResponse response = controller.update(authentication, message.id(),
                new StoreMessageRequest(null, "Promoção de inverno! 🧥", null));

        assertThat(response.text()).isEqualTo("Promo!");
        verify(messages).update(eq(MERCHANT_ID), eq(message.id()), any());
    }

    @Test
    void deleteReturns204() {
        ResponseEntity<Void> response = controller.delete(authentication, UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(messages).delete(eq(MERCHANT_ID), any());
    }

    @Test
    void scheduleReturns201WithBody() {
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promo de sexta!",
                NOW.plusSeconds(3600), null, ScheduledMessageAudience.BUYERS, NOW);
        when(messages.schedule(eq(MERCHANT_ID), any())).thenReturn(message);

        ResponseEntity<ScheduledMessageResponse> response = controller.schedule(authentication,
                new ScheduleMessageRequest("Promo de sexta!", NOW.plusSeconds(3600), null, "BUYERS"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().status()).isEqualTo(
                com.foxcash.domain.model.store.ScheduledMessageStatus.SCHEDULED);
        verify(messages).schedule(eq(MERCHANT_ID), any());
    }

    @Test
    void scheduleRejectsInvalidAudience() {
        assertThatThrownBy(() -> controller.schedule(authentication,
                new ScheduleMessageRequest("Promo!", NOW.plusSeconds(3600), null, "ALL")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
