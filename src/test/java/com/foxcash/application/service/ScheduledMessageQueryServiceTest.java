package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreMessageNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.store.ScheduledMessage;
import com.foxcash.domain.model.store.ScheduledMessageStatus;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.in.ScheduledMessageQueryUseCase;
import com.foxcash.domain.port.out.PlanDeliveryRepository;
import com.foxcash.domain.port.out.ScheduledMessageRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do serviço de consulta de mensagens programadas (Spec 51): o bot
 * busca as vencidas (one-shot por data / cron pelo próximo disparo) com os
 * {@code recipients} (platform_user_id deduplicados dos compradores entregues)
 * e confirma o envio (SENT idempotente); 404 cross-loja.
 */
class ScheduledMessageQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final ScheduledMessageRepository scheduledMessageRepository =
            mock(ScheduledMessageRepository.class);
    private final PlanDeliveryRepository deliveryRepository = mock(PlanDeliveryRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ScheduledMessageQueryService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledMessageQueryService(storeRepository, scheduledMessageRepository,
                deliveryRepository, clock);
        when(storeRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(Store.restore(STORE_ID, MERCHANT_ID, "Loja", null, null,
                        null, null, null, null, null, null, NOW, NOW)));
    }

    @Test
    void dueReturnsOnlyDueMessagesWithRecipients() {
        ScheduledMessage due = ScheduledMessage.create(STORE_ID, "Promo de sexta!",
                NOW.minusSeconds(60), null, null, NOW.minusSeconds(3600));
        ScheduledMessage notDue = ScheduledMessage.create(STORE_ID, "Promo futura!",
                NOW.plusSeconds(3600), null, null, NOW.minusSeconds(3600));
        ScheduledMessage sent = ScheduledMessage.create(STORE_ID, "Já enviada!",
                NOW.minusSeconds(60), null, null, NOW.minusSeconds(3600));
        sent.markSent(NOW.minusSeconds(30));
        when(scheduledMessageRepository.findByStoreIdAndStatus(STORE_ID, ScheduledMessageStatus.SCHEDULED))
                .thenReturn(List.of(due, notDue, sent));
        when(deliveryRepository.findDistinctDeliveredPlatformUserIds(STORE_ID))
                .thenReturn(List.of("discord_user_1", "discord_user_2"));

        List<ScheduledMessageQueryUseCase.DueScheduledMessage> result = service.due(MERCHANT_ID, NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).message().id()).isEqualTo(due.id());
        assertThat(result.get(0).recipients()).containsExactly("discord_user_1", "discord_user_2");
    }

    @Test
    void dueIncludesCronMessageWhenNextFireIsDue() {
        // Cron diário 10:00; criada ontem 12:00 — hoje 10:00 já passou (devida).
        ScheduledMessage cron = ScheduledMessage.create(STORE_ID, "Diária!",
                null, "0 0 10 * * *", null, NOW.minusSeconds(86400));
        when(scheduledMessageRepository.findByStoreIdAndStatus(STORE_ID, ScheduledMessageStatus.SCHEDULED))
                .thenReturn(List.of(cron));
        when(deliveryRepository.findDistinctDeliveredPlatformUserIds(STORE_ID)).thenReturn(List.of());

        Instant now = Instant.parse("2026-08-06T15:00:00Z");
        List<ScheduledMessageQueryUseCase.DueScheduledMessage> result = service.due(MERCHANT_ID, now);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).message().id()).isEqualTo(cron.id());
    }

    @Test
    void dueWithoutStoreThrows404() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.due(MERCHANT_ID, NOW))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void markSentMarksAndSaves() {
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promo!",
                NOW.minusSeconds(60), null, null, NOW.minusSeconds(3600));
        when(scheduledMessageRepository.findByIdAndStoreId(message.id(), STORE_ID))
                .thenReturn(Optional.of(message));
        when(scheduledMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.markSent(MERCHANT_ID, message.id());

        assertThat(message.status()).isEqualTo(ScheduledMessageStatus.SENT);
        assertThat(message.sentAt()).isEqualTo(NOW);
        verify(scheduledMessageRepository).save(message);
    }

    @Test
    void markSentIsIdempotentOnReplay() {
        ScheduledMessage message = ScheduledMessage.create(STORE_ID, "Promo!",
                NOW.minusSeconds(60), null, null, NOW.minusSeconds(3600));
        message.markSent(NOW.minusSeconds(30));
        when(scheduledMessageRepository.findByIdAndStoreId(message.id(), STORE_ID))
                .thenReturn(Optional.of(message));
        when(scheduledMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.markSent(MERCHANT_ID, message.id());

        assertThat(message.sentAt()).isEqualTo(NOW.minusSeconds(30));
        verify(scheduledMessageRepository).save(message);
    }

    @Test
    void markSentWithoutMessageThrows404() {
        UUID messageId = UUID.randomUUID();
        when(scheduledMessageRepository.findByIdAndStoreId(messageId, STORE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSent(MERCHANT_ID, messageId))
                .isInstanceOf(StoreMessageNotFoundException.class);

        verify(scheduledMessageRepository, never()).save(any());
    }
}
