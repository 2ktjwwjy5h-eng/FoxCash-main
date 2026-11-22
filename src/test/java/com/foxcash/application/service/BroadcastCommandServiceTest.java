package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.BroadcastManagementUseCase;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Testes do serviço de criação/cancelamento de broadcasts (Spec 79): valida o
 * bot do lojista (404 cross-lojista), persiste PENDING com auditoria
 * (BROADCAST_CREATED/SCHEDULED na mesma transação), valida buttons_json por
 * plataforma (URL SSRF-safe injetada) e cancela apenas PENDING (DELETE físico).
 */
class BroadcastCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();

    private final BroadcastRepository broadcastRepository = mock(BroadcastRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private BroadcastCommandService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastCommandService(broadcastRepository, botRepository,
                activityRecorder, clock);
        when(broadcastRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private StoreBot telegramBot() {
        return StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM,
                "123456:ABC-secret-token", "hash", "wh-hash", "Bot Vendas", "vendasbot", null,
                com.foxcash.domain.model.storebot.BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
    }

    private BroadcastManagementUseCase.CreateBroadcastCommand nowCommand() {
        return new BroadcastManagementUseCase.CreateBroadcastCommand(
                BOT_ID, BroadcastMode.NOW, "-100123456789", "Promoção!",
                null, null, null, null, null, null);
    }

    @Test
    void createPersistsPendingWithAudit() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));

        Broadcast created = service.create(MERCHANT_ID, nowCommand());

        assertThat(created.mode()).isEqualTo(BroadcastMode.NOW);
        assertThat(created.status()).isEqualTo(BroadcastStatus.PENDING);
        assertThat(created.targetChatId()).isEqualTo("-100123456789");
        verify(broadcastRepository).save(any(Broadcast.class));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BROADCAST_CREATED), eq("BROADCAST"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void createScheduledUsesScheduledAudit() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        BroadcastManagementUseCase.CreateBroadcastCommand command =
                new BroadcastManagementUseCase.CreateBroadcastCommand(
                        BOT_ID, BroadcastMode.SCHEDULED, "-100123456789", "Promoção",
                        null, null, null, null, NOW.plusSeconds(3600), null);

        service.create(MERCHANT_ID, command);

        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BROADCAST_SCHEDULED), eq("BROADCAST"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void createValidatesButtonsByPlatformWithSsrfSafeUrl() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        String buttons = "{\"rows\":[[{\"label\":\"X\",\"url\":\"http://8.8.8.8/loja\"}]]}";
        BroadcastManagementUseCase.CreateBroadcastCommand command =
                new BroadcastManagementUseCase.CreateBroadcastCommand(
                        BOT_ID, BroadcastMode.NOW, "-100123456789", "Promoção",
                        null, null, null, buttons, null, null);

        Broadcast created = service.create(MERCHANT_ID, command);

        assertThat(created.buttons()).isNotNull();
        assertThat(created.buttons().total()).isEqualTo(1);
    }

    @Test
    void createRejectsButtonWithInternalUrl() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        // URL de loopback → SSRF bloqueado (WebhookUrlValidator injetado).
        String buttons = "{\"rows\":[[{\"label\":\"X\",\"url\":\"http://127.0.0.1:8080/x\"}]]}";
        BroadcastManagementUseCase.CreateBroadcastCommand command =
                new BroadcastManagementUseCase.CreateBroadcastCommand(
                        BOT_ID, BroadcastMode.NOW, "-100123456789", "Promoção",
                        null, null, null, buttons, null, null);

        assertThatThrownBy(() -> service.create(MERCHANT_ID, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF");
    }

    @Test
    void createRejectsMissingBotOrCrossMerchant() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(MERCHANT_ID, nowCommand()))
                .isInstanceOf(StoreBotNotFoundException.class);
        verify(broadcastRepository, never()).save(any());
    }

    @Test
    void cancelDeletesPendingWithAudit() {
        Broadcast pending = Broadcast.restore(UUID.randomUUID(), BOT_ID, BroadcastMode.NOW,
                "-100123456789", "Promoção", null, null, null, null,
                BroadcastStatus.PENDING, null, null, null, NOW, NOW);
        when(broadcastRepository.findByIdAndMerchantId(pending.id(), MERCHANT_ID))
                .thenReturn(Optional.of(pending));

        service.cancel(MERCHANT_ID, pending.id());

        verify(broadcastRepository).delete(pending);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BROADCAST_CANCELLED), eq("BROADCAST"), eq(pending.id()),
                eq(MERCHANT_ID), any());
    }

    @Test
    void cancelRejectsProcessingBroadcast() {
        Broadcast processing = Broadcast.restore(UUID.randomUUID(), BOT_ID, BroadcastMode.NOW,
                "-100123456789", "Promoção", null, null, null, null,
                BroadcastStatus.PROCESSING, null, null, null, NOW, NOW);
        when(broadcastRepository.findByIdAndMerchantId(processing.id(), MERCHANT_ID))
                .thenReturn(Optional.of(processing));

        assertThatThrownBy(() -> service.cancel(MERCHANT_ID, processing.id()))
                .isInstanceOf(IllegalStateException.class);
        verify(broadcastRepository, never()).delete(any());
    }

    @Test
    void cancelNotFoundThrows404() {
        UUID id = UUID.randomUUID();
        when(broadcastRepository.findByIdAndMerchantId(id, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(MERCHANT_ID, id))
                .isInstanceOf(BroadcastNotFoundException.class);
    }
}
