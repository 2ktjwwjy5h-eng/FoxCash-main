package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastLogStatus;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.BroadcastDispatchUseCase;
import com.foxcash.domain.port.out.BroadcastLogRepository;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.domain.port.out.BroadcastWelcomeSentRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
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
 * Testes do dispatch de broadcasts pelo bot (Spec 79): due filtra e marca
 * PROCESSING; markSent conclui COMPLETED se ≥1 SUCCESS / FAILED se nenhum;
 * reportLog é idempotente (replay não duplica) e 404 cross-lojista;
 * welcomeTrigger devolve a mensagem WELCOME ativa 1× por (chatId, userId).
 */
class BroadcastDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID BROADCAST_ID = UUID.randomUUID();

    private final BroadcastRepository broadcastRepository = mock(BroadcastRepository.class);
    private final BroadcastLogRepository logRepository = mock(BroadcastLogRepository.class);
    private final BroadcastWelcomeSentRepository welcomeSentRepository =
            mock(BroadcastWelcomeSentRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private BroadcastDispatchService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastDispatchService(broadcastRepository, logRepository,
                welcomeSentRepository, botRepository, clock);
    }

    private Broadcast pendingBroadcast(BroadcastMode mode, Instant scheduledAt) {
        return Broadcast.restore(BROADCAST_ID, BOT_ID, mode, "-100123456789", "Promoção",
                null, null, null, null, BroadcastStatus.PENDING, null,
                scheduledAt, mode == BroadcastMode.REPOST ? 12 * 3600 : null, NOW, NOW);
    }

    private StoreBot telegramBot() {
        return StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM,
                "123456:ABC-secret-token", "hash", "wh-hash", "Bot Vendas", "vendasbot", null,
                BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
    }

    @Test
    void dueReturnsAndMarksProcessing() {
        Broadcast pending = pendingBroadcast(BroadcastMode.NOW, null);
        when(broadcastRepository.findDue(MERCHANT_ID, NOW.plusSeconds(60)))
                .thenReturn(List.of(pending));
        when(broadcastRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Broadcast> due = service.due(MERCHANT_ID, NOW.plusSeconds(60));

        assertThat(due).hasSize(1);
        verify(broadcastRepository).save(any(Broadcast.class));
        // O broadcast salvo está PROCESSING.
        when(broadcastRepository.findDue(MERCHANT_ID, NOW.plusSeconds(60)))
                .thenReturn(List.of());
    }

    @Test
    void markSentCompletesWhenAnySuccess() {
        Broadcast processing = pendingBroadcast(BroadcastMode.NOW, null);
        processing.markProcessing(NOW);
        when(broadcastRepository.findByIdAndMerchantId(BROADCAST_ID, MERCHANT_ID))
                .thenReturn(Optional.of(processing));
        when(logRepository.countByBroadcastIdAndStatus(BROADCAST_ID, BroadcastLogStatus.SUCCESS))
                .thenReturn(2L);
        when(broadcastRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.markSent(MERCHANT_ID, BROADCAST_ID);

        verify(broadcastRepository).save(any(Broadcast.class));
    }

    @Test
    void markSentFailsWhenNoSuccess() {
        Broadcast processing = pendingBroadcast(BroadcastMode.NOW, null);
        processing.markProcessing(NOW);
        when(broadcastRepository.findByIdAndMerchantId(BROADCAST_ID, MERCHANT_ID))
                .thenReturn(Optional.of(processing));
        when(logRepository.countByBroadcastIdAndStatus(BROADCAST_ID, BroadcastLogStatus.SUCCESS))
                .thenReturn(0L);
        when(broadcastRepository.save(any())).thenAnswer(inv -> {
            Broadcast b = inv.getArgument(0);
            assertThat(b.status()).isEqualTo(BroadcastStatus.FAILED);
            assertThat(b.errorMessage()).contains("SUCCESS");
            return b;
        });

        service.markSent(MERCHANT_ID, BROADCAST_ID);
    }

    @Test
    void markSentIsIdempotentForAlreadyCompleted() {
        Broadcast completed = pendingBroadcast(BroadcastMode.NOW, null);
        completed.markProcessing(NOW);
        completed.complete(NOW);
        when(broadcastRepository.findByIdAndMerchantId(BROADCAST_ID, MERCHANT_ID))
                .thenReturn(Optional.of(completed));

        service.markSent(MERCHANT_ID, BROADCAST_ID);

        verify(broadcastRepository, never()).save(any());
    }

    @Test
    void reportLogSavesOnceAndIsIdempotent() {
        when(broadcastRepository.findByIdAndMerchantId(BROADCAST_ID, MERCHANT_ID))
                .thenReturn(Optional.of(pendingBroadcast(BroadcastMode.NOW, null)));
        when(logRepository.findByBroadcastIdAndRecipientId(BROADCAST_ID, "12345"))
                .thenReturn(Optional.empty());

        service.reportLog(MERCHANT_ID, BROADCAST_ID, "12345",
                BroadcastLogStatus.SUCCESS, null);

        verify(logRepository).save(any());

        // Replay (já gravado) → no-op.
        when(logRepository.findByBroadcastIdAndRecipientId(BROADCAST_ID, "12345"))
                .thenReturn(Optional.of(com.foxcash.domain.model.broadcast.BroadcastLog.create(
                        BROADCAST_ID, "12345", BroadcastLogStatus.SUCCESS, null, NOW)));
        service.reportLog(MERCHANT_ID, BROADCAST_ID, "12345",
                BroadcastLogStatus.SUCCESS, null);
        verify(logRepository).save(any()); // ainda 1×
    }

    @Test
    void reportLogFailureOfOneRecipientDoesNotBreakOthers() {
        when(broadcastRepository.findByIdAndMerchantId(BROADCAST_ID, MERCHANT_ID))
                .thenReturn(Optional.of(pendingBroadcast(BroadcastMode.NOW, null)));
        when(logRepository.findByBroadcastIdAndRecipientId(any(), any()))
                .thenReturn(Optional.empty());

        service.reportLog(MERCHANT_ID, BROADCAST_ID, "1", BroadcastLogStatus.SUCCESS, null);
        service.reportLog(MERCHANT_ID, BROADCAST_ID, "2", BroadcastLogStatus.FAILED, "boom");
        service.reportLog(MERCHANT_ID, BROADCAST_ID, "3", BroadcastLogStatus.BLOCKED, "blocked");

        // Cada destinatário grava seu log; a falha de um NUNCA derruba os demais.
        verify(logRepository, org.mockito.Mockito.times(3)).save(any());
    }

    @Test
    void reportLogCrossMerchantThrows404() {
        when(broadcastRepository.findByIdAndMerchantId(BROADCAST_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportLog(MERCHANT_ID, BROADCAST_ID, "12345",
                BroadcastLogStatus.SUCCESS, null))
                .isInstanceOf(BroadcastNotFoundException.class);
        verify(logRepository, never()).save(any());
    }

    @Test
    void rescheduleCompletedRepostsRequeuesAndReschedules() {
        Broadcast completed = pendingBroadcast(BroadcastMode.REPOST, NOW.minusSeconds(60));
        completed.markProcessing(NOW);
        completed.complete(NOW);
        when(broadcastRepository.findCompletedReposts()).thenReturn(List.of(completed));
        when(broadcastRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rescheduleCompletedReposts();

        verify(broadcastRepository).save(any(Broadcast.class));
        // O REPOST volta a PENDING com scheduled_at = now + interval_seconds.
        when(broadcastRepository.save(any())).thenAnswer(inv -> {
            Broadcast saved = inv.getArgument(0);
            assertThat(saved.status()).isEqualTo(BroadcastStatus.PENDING);
            assertThat(saved.scheduledAt()).isEqualTo(NOW.plusSeconds(12 * 3600));
            return saved;
        });
        service.rescheduleCompletedReposts();
    }

    @Test
    void fullFlowDueReportLogsThenSent() {
        // E2E do ciclo NOW: due (PENDING → PROCESSING) → logs por destinatário
        // (SUCCESS/BLOCKED) → markSent (COMPLETED por ter ≥1 SUCCESS).
        Broadcast pending = pendingBroadcast(BroadcastMode.NOW, null);
        when(broadcastRepository.findDue(MERCHANT_ID, NOW.plusSeconds(60)))
                .thenReturn(List.of(pending));
        when(broadcastRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 1. O bot busca as pendências (vira PROCESSING).
        List<Broadcast> due = service.due(MERCHANT_ID, NOW.plusSeconds(60));
        assertThat(due).hasSize(1);

        // 2. O bot envia e reporta o log por destinatário.
        when(broadcastRepository.findByIdAndMerchantId(BROADCAST_ID, MERCHANT_ID))
                .thenReturn(Optional.of(due.get(0)));
        when(logRepository.findByBroadcastIdAndRecipientId(any(), any()))
                .thenReturn(Optional.empty());
        service.reportLog(MERCHANT_ID, BROADCAST_ID, "user1", BroadcastLogStatus.SUCCESS, null);
        service.reportLog(MERCHANT_ID, BROADCAST_ID, "user2", BroadcastLogStatus.BLOCKED, "blocked");

        // 3. Confirma o envio: ≥1 SUCCESS → COMPLETED (2º save — o 1º foi o due).
        when(logRepository.countByBroadcastIdAndStatus(BROADCAST_ID, BroadcastLogStatus.SUCCESS))
                .thenReturn(1L);
        service.markSent(MERCHANT_ID, BROADCAST_ID);
        verify(broadcastRepository, org.mockito.Mockito.times(2)).save(any(Broadcast.class));
    }

    @Test
    void welcomeTriggerReturnsActiveMessageOncePerUser() {
        Broadcast welcome = Broadcast.restore(UUID.randomUUID(), BOT_ID, BroadcastMode.WELCOME,
                null, "Bem-vindo ao canal!", null, null, null, null,
                BroadcastStatus.PENDING, null, null, null, NOW, NOW);
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        when(broadcastRepository.findWelcomeForChat(BOT_ID, "-1001")).thenReturn(List.of(welcome));
        when(welcomeSentRepository.existsByChatIdAndUserId("-1001", "user42")).thenReturn(false);

        BroadcastDispatchUseCase.WelcomeResult result =
                service.welcomeTrigger(MERCHANT_ID, BOT_ID, "-1001", "user42");

        assertThat(result).isNotNull();
        assertThat(result.broadcastId()).isEqualTo(welcome.id());
        assertThat(result.content()).isEqualTo("Bem-vindo ao canal!");
        verify(welcomeSentRepository).record(welcome.id(), "-1001", "user42");
    }

    @Test
    void welcomeTriggerIsNoOpWhenAlreadySent() {
        when(welcomeSentRepository.existsByChatIdAndUserId("-1001", "user42")).thenReturn(true);

        BroadcastDispatchUseCase.WelcomeResult result =
                service.welcomeTrigger(MERCHANT_ID, BOT_ID, "-1001", "user42");

        assertThat(result).isNull();
        verify(welcomeSentRepository, never()).record(any(), any(), any());
    }

    @Test
    void welcomeTriggerIsNoOpWhenNoActiveMessage() {
        when(welcomeSentRepository.existsByChatIdAndUserId("-1001", "user42")).thenReturn(false);
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        when(broadcastRepository.findWelcomeForChat(BOT_ID, "-1001")).thenReturn(List.of());

        BroadcastDispatchUseCase.WelcomeResult result =
                service.welcomeTrigger(MERCHANT_ID, BOT_ID, "-1001", "user42");

        assertThat(result).isNull();
        verify(welcomeSentRepository, never()).record(any(), any(), any());
    }

    @Test
    void welcomeTriggerCrossMerchantBotThrows404() {
        when(welcomeSentRepository.existsByChatIdAndUserId("-1001", "user42")).thenReturn(false);
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.welcomeTrigger(MERCHANT_ID, BOT_ID, "-1001", "user42"))
                .isInstanceOf(StoreBotNotFoundException.class);
    }
}
