package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreMessageNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageAudience;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;
import com.foxcash.domain.port.in.StoreMessageManagementUseCase;
import com.foxcash.domain.port.out.ScheduledMessageRepository;
import com.foxcash.domain.port.out.StoreMessageRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do serviço de mensagens da loja do portal (Spec 51): lista/consulta/
 * upsert por event (201 na criação, 200 na atualização)/atualiza/remove/agenda
 * — com auditoria {@code MESSAGE_CREATED/UPDATED/DELETED/SCHEDULED} na mesma
 * transação e o {@code text} (pode conter link privado) nunca em claro na
 * trilha.
 */
class StoreMessageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreMessageRepository messageRepository = mock(StoreMessageRepository.class);
    private final ScheduledMessageRepository scheduledMessageRepository =
            mock(ScheduledMessageRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private StoreMessageService service;

    @BeforeEach
    void setUp() {
        service = new StoreMessageService(storeRepository, messageRepository,
                scheduledMessageRepository, activityRecorder, clock);
        when(storeRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(Store.restore(STORE_ID, MERCHANT_ID, "Loja", null, null,
                        null, null, null, null, null, null, NOW, NOW)));
    }

    private StoreMessageManagementUseCase.SaveMessageCommand welcomeCommand(String text) {
        return new StoreMessageManagementUseCase.SaveMessageCommand(
                StoreMessageEvent.WELCOME, text, null);
    }

    @Test
    void listReturnsMessagesOfStore() {
        StoreMessage welcome = StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME, "Oi!", NOW);
        when(messageRepository.findByStoreId(STORE_ID)).thenReturn(List.of(welcome));

        List<StoreMessage> result = service.list(MERCHANT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).event()).isEqualTo(StoreMessageEvent.WELCOME);
    }

    @Test
    void getReturnsMessageOfStore() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.FAQ, "Dúvidas?", NOW);
        when(messageRepository.findByIdAndStoreId(message.id(), STORE_ID))
                .thenReturn(Optional.of(message));

        StoreMessage result = service.get(MERCHANT_ID, message.id());

        assertThat(result.id()).isEqualTo(message.id());
    }

    @Test
    void getWithoutMessageThrows404() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findByIdAndStoreId(messageId, STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MERCHANT_ID, messageId))
                .isInstanceOf(StoreMessageNotFoundException.class);
    }

    @Test
    void upsertCreatesWhenEventNotPresent() {
        when(messageRepository.findByStoreIdAndEvent(STORE_ID, StoreMessageEvent.WELCOME))
                .thenReturn(Optional.empty());
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreMessageManagementUseCase.UpsertResult result =
                service.upsert(MERCHANT_ID, welcomeCommand("Olá 👋 Bem-vindo."));

        assertThat(result.created()).isTrue();
        assertThat(result.message().event()).isEqualTo(StoreMessageEvent.WELCOME);
        assertThat(result.message().text()).isEqualTo("Olá 👋 Bem-vindo.");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MESSAGE_CREATED), eq("STORE_MESSAGE"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void upsertUpdatesWhenEventAlreadyExists() {
        StoreMessage existing = StoreMessage.create(STORE_ID, StoreMessageEvent.WELCOME, "Oi.", NOW);
        when(messageRepository.findByStoreIdAndEvent(STORE_ID, StoreMessageEvent.WELCOME))
                .thenReturn(Optional.of(existing));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreMessageManagementUseCase.UpsertResult result =
                service.upsert(MERCHANT_ID, welcomeCommand("Olá 👋 Bem-vindo à loja!"));

        assertThat(result.created()).isFalse();
        assertThat(result.message().text()).isEqualTo("Olá 👋 Bem-vindo à loja!");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MESSAGE_UPDATED), eq("STORE_MESSAGE"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void upsertWithoutEventThrows400() {
        StoreMessageManagementUseCase.SaveMessageCommand command =
                new StoreMessageManagementUseCase.SaveMessageCommand(null, "Oi.", null);

        assertThatThrownBy(() -> service.upsert(MERCHANT_ID, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event e obrigatorio");

        verify(messageRepository, never()).save(any());
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void upsertWithoutStoreThrows404() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(MERCHANT_ID, welcomeCommand("Oi.")))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void updateChangesFieldsAndRecordsActivity() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.PROMO, "Promo!", NOW);
        when(messageRepository.findByIdAndStoreId(message.id(), STORE_ID))
                .thenReturn(Optional.of(message));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreMessage updated = service.update(MERCHANT_ID, message.id(),
                new StoreMessageManagementUseCase.SaveMessageCommand(null,
                        "Promoção de inverno! 🧥", null));

        assertThat(updated.text()).isEqualTo("Promoção de inverno! 🧥");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MESSAGE_UPDATED), eq("STORE_MESSAGE"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void updateWithoutMessageThrows404() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findByIdAndStoreId(messageId, STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MERCHANT_ID, messageId, welcomeCommand("Oi.")))
                .isInstanceOf(StoreMessageNotFoundException.class);
    }

    @Test
    void deleteRemovesAndRecordsActivity() {
        StoreMessage message = StoreMessage.create(STORE_ID, StoreMessageEvent.FAQ, "Dúvidas?", NOW);
        when(messageRepository.findByIdAndStoreId(message.id(), STORE_ID))
                .thenReturn(Optional.of(message));

        service.delete(MERCHANT_ID, message.id());

        verify(messageRepository).delete(message);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MESSAGE_DELETED), eq("STORE_MESSAGE"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void scheduleCreatesAndRecordsActivity() {
        when(scheduledMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledMessage created = service.schedule(MERCHANT_ID,
                new StoreMessageManagementUseCase.ScheduleMessageCommand(
                        "Promo de sexta!", NOW.plusSeconds(3600), null,
                        ScheduledMessageAudience.BUYERS));

        assertThat(created.status()).isEqualTo(com.foxcash.domain.model.store.ScheduledMessageStatus.SCHEDULED);
        assertThat(created.scheduledAt()).isEqualTo(NOW.plusSeconds(3600));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.MESSAGE_SCHEDULED), eq("SCHEDULED_MESSAGE"), any(),
                eq(MERCHANT_ID), any());
    }

    @Test
    void scheduleRejectsInvalidSchedule() {
        assertThatThrownBy(() -> service.schedule(MERCHANT_ID,
                new StoreMessageManagementUseCase.ScheduleMessageCommand(
                        "Promo!", null, null, ScheduledMessageAudience.BUYERS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exatamente um");

        verify(scheduledMessageRepository, never()).save(any());
    }
}
