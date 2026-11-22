package com.foxcash.domain.model.broadcast;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do aggregate {@link Broadcast} (Spec 79): validações de criação por
 * modo (target_chat_id obrigatório; scheduled_at futuro no SCHEDULED;
 * interval_seconds >= 3600 no REPOST; conteúdo e/ou mídia), máquina de estados
 * (PENDING → PROCESSING → COMPLETED/FAILED; reschedule do REPOST) e
 * elegibilidade (isDue).
 */
class BroadcastTest {

    private static final UUID STORE_BOT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    private Broadcast nowBroadcast() {
        return Broadcast.create(STORE_BOT_ID, BroadcastMode.NOW, "-100123456789",
                "Promoção relâmpago!", null, null, null, null, null, null, NOW);
    }

    @Test
    void createNowRequiresTargetChatAndStatusPending() {
        Broadcast broadcast = nowBroadcast();

        assertThat(broadcast.mode()).isEqualTo(BroadcastMode.NOW);
        assertThat(broadcast.targetChatId()).isEqualTo("-100123456789");
        assertThat(broadcast.content()).isEqualTo("Promoção relâmpago!");
        assertThat(broadcast.status()).isEqualTo(BroadcastStatus.PENDING);
        assertThat(broadcast.scheduledAt()).isNull();
        assertThat(broadcast.intervalSeconds()).isNull();
        assertThat(broadcast.createdAt()).isEqualTo(NOW);
        assertThat(broadcast.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void createRejectsMissingTargetChatForChannelModes() {
        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.NOW, null,
                "texto", null, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target_chat_id");

        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.SCHEDULED, " ",
                "texto", null, null, null, null, NOW.plusSeconds(3600), null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target_chat_id");

        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.REPOST, null,
                "texto", null, null, null, null, null, 7200, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target_chat_id");
    }

    @Test
    void welcomeDoesNotRequireTargetChat() {
        Broadcast welcome = Broadcast.create(STORE_BOT_ID, BroadcastMode.WELCOME, null,
                "Bem-vindo ao canal!", null, null, null, null, null, null, NOW);

        assertThat(welcome.mode()).isEqualTo(BroadcastMode.WELCOME);
        assertThat(welcome.targetChatId()).isNull();
        assertThat(welcome.status()).isEqualTo(BroadcastStatus.PENDING);
    }

    @Test
    void createRejectsMissingContentAndMedia() {
        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.NOW, "-100x",
                "   ", null, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");

        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.NOW, "-100x",
                null, null, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void createAcceptsMediaOnlyBroadcast() {
        Broadcast broadcast = Broadcast.create(STORE_BOT_ID, BroadcastMode.NOW, "-100x",
                null, "https://cdn.example.com/banner.jpg", BroadcastMediaType.IMAGE,
                null, null, null, null, NOW);

        assertThat(broadcast.mediaUrl()).isEqualTo("https://cdn.example.com/banner.jpg");
        assertThat(broadcast.mediaType()).isEqualTo(BroadcastMediaType.IMAGE);
        assertThat(broadcast.content()).isEmpty();
    }

    @Test
    void scheduledRequiresFutureScheduledAt() {
        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.SCHEDULED, "-100x",
                "texto", null, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduled_at");

        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.SCHEDULED, "-100x",
                "texto", null, null, null, null, NOW.minusSeconds(60), null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no futuro");

        Broadcast broadcast = Broadcast.create(STORE_BOT_ID, BroadcastMode.SCHEDULED, "-100x",
                "texto", null, null, null, null, NOW.plusSeconds(7200), null, NOW);
        assertThat(broadcast.scheduledAt()).isEqualTo(NOW.plusSeconds(7200));
    }

    @Test
    void repostRequiresIntervalAtLeastOneHour() {
        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.REPOST, "-100x",
                "texto", null, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval_seconds");

        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.REPOST, "-100x",
                "texto", null, null, null, null, null, 3599, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval_seconds");

        Broadcast broadcast = Broadcast.create(STORE_BOT_ID, BroadcastMode.REPOST, "-100x",
                "texto", null, null, null, null, null, 12 * 3600, NOW);
        assertThat(broadcast.intervalSeconds()).isEqualTo(12 * 3600);
    }

    @Test
    void intervalSecondsIsExclusiveToRepost() {
        assertThatThrownBy(() -> Broadcast.create(STORE_BOT_ID, BroadcastMode.NOW, "-100x",
                "texto", null, null, null, null, null, 7200, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REPOST");
    }

    @Test
    void stateMachineProcessingComplete() {
        Broadcast broadcast = nowBroadcast();

        assertThat(broadcast.markProcessing(NOW.plusSeconds(1))).isTrue();
        assertThat(broadcast.status()).isEqualTo(BroadcastStatus.PROCESSING);
        // Replay da marcação de PROCESSING → no-op.
        assertThat(broadcast.markProcessing(NOW.plusSeconds(2))).isFalse();

        assertThat(broadcast.complete(NOW.plusSeconds(3))).isTrue();
        assertThat(broadcast.status()).isEqualTo(BroadcastStatus.COMPLETED);
        assertThat(broadcast.errorMessage()).isNull();
        // Replay de conclusão → no-op.
        assertThat(broadcast.complete(NOW.plusSeconds(4))).isFalse();
    }

    @Test
    void stateMachineFailStoresErrorMessage() {
        Broadcast broadcast = nowBroadcast();
        broadcast.markProcessing(NOW.plusSeconds(1));

        assertThat(broadcast.fail("blocked by platform", NOW.plusSeconds(2))).isTrue();
        assertThat(broadcast.status()).isEqualTo(BroadcastStatus.FAILED);
        assertThat(broadcast.errorMessage()).isEqualTo("blocked by platform");
    }

    @Test
    void failIsOnlyValidFromProcessing() {
        Broadcast broadcast = nowBroadcast();
        // PENDING direto para FAILED → no-op (transição inválida não muda nada).
        assertThat(broadcast.fail("x", NOW)).isFalse();
        assertThat(broadcast.status()).isEqualTo(BroadcastStatus.PENDING);
    }

    @Test
    void forceCancelMarksPendingAsFailedWithReason() {
        Broadcast broadcast = nowBroadcast();
        // Spec 89: PENDING → FAILED com reason (master) — aditivo ao fail().
        assertThat(broadcast.forceCancel("Cancelled by Master Operator", NOW)).isTrue();
        assertThat(broadcast.status()).isEqualTo(BroadcastStatus.FAILED);
        assertThat(broadcast.errorMessage()).isEqualTo("Cancelled by Master Operator");
        assertThat(broadcast.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void forceCancelIsOnlyValidFromPending() {
        // PROCESSING não pode ser forçado (o service valida e lança 409 antes).
        Broadcast processing = nowBroadcast();
        processing.markProcessing(NOW.plusSeconds(1));
        assertThat(processing.forceCancel("x", NOW.plusSeconds(2))).isFalse();
        assertThat(processing.status()).isEqualTo(BroadcastStatus.PROCESSING);
    }

    @Test
    void rescheduleOnlyForRepostCompleted() {
        Broadcast repost = Broadcast.create(STORE_BOT_ID, BroadcastMode.REPOST, "-100x",
                "texto", null, null, null, null, null, 12 * 3600, NOW);
        // PENDING (não concluído) → sem reschedule.
        assertThat(repost.reschedule(NOW)).isFalse();

        repost.markProcessing(NOW.plusSeconds(1));
        repost.complete(NOW.plusSeconds(2));
        assertThat(repost.reschedule(NOW.plusSeconds(3))).isTrue();
        assertThat(repost.status()).isEqualTo(BroadcastStatus.PENDING);
        assertThat(repost.scheduledAt()).isEqualTo(NOW.plusSeconds(3).plusSeconds(12 * 3600));
    }

    @Test
    void rescheduleRejectedForNonRepost() {
        Broadcast now = nowBroadcast();
        now.markProcessing(NOW.plusSeconds(1));
        now.complete(NOW.plusSeconds(2));

        assertThat(now.reschedule(NOW.plusSeconds(3))).isFalse();
        assertThat(now.status()).isEqualTo(BroadcastStatus.COMPLETED);
    }

    @Test
    void cancelOnlyWhenPending() {
        Broadcast broadcast = nowBroadcast();
        assertThat(broadcast.isCancellable()).isTrue();

        broadcast.markProcessing(NOW.plusSeconds(1));
        assertThat(broadcast.isCancellable()).isFalse();
    }

    @Test
    void isDueForNowWithoutSchedule() {
        Broadcast broadcast = nowBroadcast();
        assertThat(broadcast.isDue(NOW.plusSeconds(10))).isTrue();
    }

    @Test
    void isDueOnlyWhenPending() {
        Broadcast broadcast = nowBroadcast();
        broadcast.markProcessing(NOW.plusSeconds(1));

        assertThat(broadcast.isDue(NOW.plusSeconds(10))).isFalse();
    }

    @Test
    void isDueForScheduledBeforeDueBefore() {
        Broadcast broadcast = Broadcast.create(STORE_BOT_ID, BroadcastMode.SCHEDULED, "-100x",
                "texto", null, null, null, null, NOW.plusSeconds(3600), null, NOW);

        // Ainda não vencida.
        assertThat(broadcast.isDue(NOW.plusSeconds(3599))).isFalse();
        // Vencida exatamente em dueBefore.
        assertThat(broadcast.isDue(NOW.plusSeconds(3600))).isTrue();
        assertThat(broadcast.isDue(NOW.plusSeconds(7200))).isTrue();
    }

    @Test
    void welcomeIsNeverDue() {
        Broadcast welcome = Broadcast.create(STORE_BOT_ID, BroadcastMode.WELCOME, null,
                "Bem-vindo!", null, null, null, null, null, null, NOW);

        assertThat(welcome.isDue(NOW.plusSeconds(999999))).isFalse();
    }
}
