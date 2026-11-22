package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.StoreMessageNotFoundException;
import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageAudience;
import com.foxcash.domain.model.store.ScheduledMessageStatus;
import com.foxcash.domain.port.in.ScheduledMessageQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ScheduledMessageDueResponse;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST das mensagens programadas do bot (Spec 51): o
 * merchantId vem do principal (ROLE_MERCHANT/API key), GET devolve as vencidas
 * com recipients, POST /{id}/sent confirma (204); dueBefore inválido → 400 e
 * 404 de domínio propagam.
 */
class FoxScheduledMessageControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private final ScheduledMessageQueryUseCase scheduledMessages =
            mock(ScheduledMessageQueryUseCase.class);
    private final Authentication authentication = mock(Authentication.class);

    private FoxScheduledMessageController controller;

    @BeforeEach
    void setUp() {
        controller = new FoxScheduledMessageController(scheduledMessages);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    @Test
    void dueReturnsDueMessagesWithRecipients() {
        ScheduledMessage due = ScheduledMessage.create(STORE_ID, "Promo de sexta!",
                NOW.minusSeconds(60), null, ScheduledMessageAudience.BUYERS, NOW.minusSeconds(3600));
        when(scheduledMessages.due(MERCHANT_ID, NOW))
                .thenReturn(List.of(new ScheduledMessageQueryUseCase.DueScheduledMessage(
                        due, List.of("discord_user_1", "discord_user_2"))));

        List<ScheduledMessageDueResponse> response =
                controller.due(authentication, "2026-08-06T12:00:00Z");

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo(due.id());
        assertThat(response.get(0).text()).isEqualTo("Promo de sexta!");
        assertThat(response.get(0).recipients()).containsExactly("discord_user_1", "discord_user_2");
        verify(scheduledMessages).due(MERCHANT_ID, NOW);
    }

    @Test
    void dueRejectsInvalidDueBefore() {
        assertThatThrownBy(() -> controller.due(authentication, "not-a-date"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markSentReturns204() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.markSent(authentication, id);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(scheduledMessages).markSent(MERCHANT_ID, id);
    }

    @Test
    void markSentPropagates404() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new StoreMessageNotFoundException())
                .when(scheduledMessages).markSent(MERCHANT_ID, id);

        assertThatThrownBy(() -> controller.markSent(authentication, id))
                .isInstanceOf(StoreMessageNotFoundException.class);
    }
}
