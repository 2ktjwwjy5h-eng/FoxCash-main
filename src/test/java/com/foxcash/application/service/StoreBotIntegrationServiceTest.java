package com.foxcash.application.service;

import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TelegramBotApi;
import com.foxcash.infrastructure.config.BotProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da integração dos bots de venda (Spec 74): Telegram (getMe +
 * setWebhook com URL {@code /webhooks/telegram/{id}/{secret}} e secret_token),
 * Discord (conexão), disintegrate best-effort e o job de reconciliação
 * (REGISTERING preso → ERROR; ACTIVE sem health → re-verifica; ERROR → re-integra).
 */
class StoreBotIntegrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();

    private final TelegramBotApi telegramApi = mock(TelegramBotApi.class);
    private final DiscordBotManager discordBotManager = mock(DiscordBotManager.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final BotProperties properties = new BotProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private StoreBotIntegrationService service;

    @BeforeEach
    void setUp() {
        service = new StoreBotIntegrationService(telegramApi, discordBotManager, botRepository,
                properties, clock, new SimpleMeterRegistry());
    }

    private StoreBot telegramBot(String webhookSecret) {
        return StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM, "123456:ABC-token",
                "hash", "wh-hash", null, "Bot Vendas", "vendasbot", null,
                BotStatus.REGISTERING, null, null, null, NOW, NOW);
    }

    private StoreBot telegramBotWithSecret() {
        StoreBot bot = telegramBot("secret-64-hex-chars");
        return bot;
    }

    @Test
    void integrateTelegramCallsGetMeAndSetWebhookWithSecretUrl() {
        StoreBot bot = telegramBotWithSecret();
        when(telegramApi.getMe("123456:ABC-token"))
                .thenReturn(new TelegramBotApi.TelegramUserInfo(42L, "vendasbot", "Bot Vendas"));

        service.integrate(bot);

        verify(telegramApi).getMe("123456:ABC-token");
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secret = ArgumentCaptor.forClass(String.class);
        verify(telegramApi).setWebhook(any(), url.capture(), secret.capture());
        // A URL carrega o id + o SEGREDO (nunca o id sozinho como segredo).
        assertThat(url.getValue()).contains("/webhooks/telegram/" + BOT_ID + "/" + bot.webhookSecret());
        // Auditoria 2026-08-18: o secret_token do header é INDEPENDENTE da URL.
        assertThat(secret.getValue()).isEqualTo(bot.webhookSecretToken());
        assertThat(secret.getValue()).isNotEqualTo(bot.webhookSecret());
        assertThat(bot.lastHealthCheckAt()).isEqualTo(NOW);
    }

    @Test
    void integrateTelegramGeneratesNewSecretWhenRestoredFromDb() {
        // Após reload do banco os valores em claro são null (só os hashes são
        // persistidos) — a integração gera URL secret E secret token novos.
        StoreBot bot = telegramBot(null);
        when(telegramApi.getMe(any())).thenReturn(new TelegramBotApi.TelegramUserInfo(1L, "u", "n"));

        service.integrate(bot);

        verify(telegramApi).setWebhook(any(), any(), any());
        assertThat(bot.webhookSecret()).isNotBlank();
        assertThat(bot.webhookSecretHash()).isNotBlank();
        assertThat(bot.webhookSecretToken()).isNotBlank();
        assertThat(bot.webhookSecretTokenHash()).isNotBlank();
        // URL usa o secret novo (o hash persistido é atualizado).
        assertThat(bot.webhookSecretHash()).isEqualTo(
                com.foxcash.infrastructure.security.Sha256Hasher.hex(bot.webhookSecret()));
        assertThat(bot.webhookSecretTokenHash()).isEqualTo(
                com.foxcash.infrastructure.security.Sha256Hasher.hex(bot.webhookSecretToken()));
    }

    @Test
    void integrateDiscordConnectsViaManager() {
        StoreBot bot = StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.DISCORD, "d-token",
                "h", "wh", "Bot D", "meubot", "app-1", BotStatus.REGISTERING, null, null, null, NOW, NOW);
        when(discordBotManager.connect(BOT_ID, "d-token", "app-1"))
                .thenReturn(new DiscordBotApi.DiscordUserInfo(1L, "meubot", "Bot D"));

        service.integrate(bot);

        verify(discordBotManager).connect(BOT_ID, "d-token", "app-1");
        assertThat(bot.username()).isEqualTo("meubot");
        assertThat(bot.lastHealthCheckAt()).isEqualTo(NOW);
    }

    @Test
    void disintegrateTelegramCallsDeleteWebhook() {
        StoreBot bot = telegramBotWithSecret();

        service.disintegrate(bot);

        verify(telegramApi).deleteWebhook("123456:ABC-token");
    }

    @Test
    void disintegrateTelegramFailureIsLoggedNotThrown() {
        StoreBot bot = telegramBotWithSecret();
        doThrow(new IllegalStateException("rede caiu")).when(telegramApi).deleteWebhook(any());

        service.disintegrate(bot); // não lança (best-effort)

        verify(telegramApi).deleteWebhook(any());
    }

    @Test
    void disintegrateDiscordDisconnects() {
        StoreBot bot = StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.DISCORD, "d-token",
                "h", "wh", "Bot D", null, null, BotStatus.ACTIVE, null, null, null, NOW, NOW);

        service.disintegrate(bot);

        verify(discordBotManager).disconnect(BOT_ID);
    }

    // ---- Reconciliação ----

    @Test
    void reconcileMarksStuckRegisteringAsError() {
        StoreBot stuck = telegramBotWithSecret();
        stuck = StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM, "t", "h", "wh",
                "Bot", null, null, BotStatus.REGISTERING, null, null, null,
                NOW.minus(java.time.Duration.ofHours(2)), NOW.minus(java.time.Duration.ofHours(2)));
        when(botRepository.findAllNonDeleted()).thenReturn(List.of(stuck));

        service.reconcile();

        assertThat(stuck.status()).isEqualTo(BotStatus.ERROR);
        verify(botRepository).save(stuck);
    }

    @Test
    void reconcileReintegratesActiveWithStaleHealth() {
        StoreBot active = telegramBotWithSecret();
        active = StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM, "t", "h", "wh",
                "Bot", null, null, BotStatus.ACTIVE, null,
                NOW.minus(java.time.Duration.ofHours(5)), null, NOW, NOW);
        when(botRepository.findAllNonDeleted()).thenReturn(List.of(active));
        when(telegramApi.getMe("t")).thenReturn(new TelegramBotApi.TelegramUserInfo(1L, "u", "n"));

        service.reconcile();

        assertThat(active.status()).isEqualTo(BotStatus.ACTIVE);
        assertThat(active.lastHealthCheckAt()).isEqualTo(NOW);
        verify(telegramApi).setWebhook(any(), any(), any());
    }

    @Test
    void reconcileRecoversErrorBotWithValidToken() {
        StoreBot errored = telegramBotWithSecret();
        errored = StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM, "t", "h", "wh",
                "Bot", null, null, BotStatus.ERROR, "falha", null, null, NOW, NOW);
        when(botRepository.findAllNonDeleted()).thenReturn(List.of(errored));
        when(telegramApi.getMe("t")).thenReturn(new TelegramBotApi.TelegramUserInfo(1L, "u", "n"));

        service.reconcile();

        assertThat(errored.status()).isEqualTo(BotStatus.ACTIVE);
        assertThat(errored.errorMessage()).isNull();
    }

    @Test
    void reconcileIgnoresPausedBots() {
        StoreBot paused = telegramBotWithSecret();
        paused = StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM, "t", "h", "wh",
                "Bot", null, null, BotStatus.PAUSED, null, null, null, NOW, NOW);
        when(botRepository.findAllNonDeleted()).thenReturn(List.of(paused));

        service.reconcile();

        verify(telegramApi, never()).getMe(any());
        verify(telegramApi, never()).setWebhook(any(), any(), any());
    }

    @Test
    void reconcileDisabledIsNoOp() {
        properties.setReconcileEnabled(false);
        when(botRepository.findAllNonDeleted()).thenReturn(List.of(telegramBotWithSecret()));

        service.reconcile();

        verify(botRepository, never()).save(any());
        verify(telegramApi, never()).getMe(any());
    }
}
