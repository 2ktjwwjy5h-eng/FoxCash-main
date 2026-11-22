package com.foxcash.domain.model.storebot;

import com.foxcash.domain.exception.InvalidBotStateException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do modelo de domínio do bot de venda (Spec 74): criação com
 * validações, restauração, máquina de estados (transições válidas/inválidas),
 * token mascarado/hash e soft-delete.
 */
class StoreBotTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    private StoreBot telegramBot() {
        return StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "123456:ABC-secret-token",
                "abc123hash", "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", "def456hash", "Bot Vendas", NOW);
    }

    @Test
    void createSetsRegisteringStatusAndFields() {
        StoreBot bot = telegramBot();

        assertThat(bot.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(bot.platform()).isEqualTo(BotPlatform.TELEGRAM);
        assertThat(bot.status()).isEqualTo(BotStatus.REGISTERING);
        assertThat(bot.displayName()).isEqualTo("Bot Vendas");
        assertThat(bot.token()).isEqualTo("123456:ABC-secret-token");
        assertThat(bot.tokenHash()).isEqualTo("abc123hash");
        assertThat(bot.webhookSecretHash()).isEqualTo("def456hash");
        assertThat(bot.isDeleted()).isFalse();
        assertThat(bot.createdAt()).isEqualTo(NOW);
        assertThat(bot.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void createRequiresTokenAndDisplayName() {
        assertThatThrownBy(() -> StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, null, "h",
                "s", "sh", "Bot", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "   ", "h",
                "s", "sh", "Bot", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "tok", "h",
                "s", "sh", null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreBot.create(null, BotPlatform.TELEGRAM, "tok", "h",
                "s", "sh", "Bot", NOW)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void createRejectsBlankHashes() {
        assertThatThrownBy(() -> StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "tok", " ",
                "s", "sh", "Bot", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "tok", "h",
                "s", null, "Bot", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenMaskShowsLastFourOnly() {
        StoreBot bot = StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "1234567890ABCD",
                "h", "s", "sh", "Bot", NOW);

        assertThat(bot.tokenMask()).isEqualTo("****ABCD");
        assertThat(bot.tokenMask()).doesNotContain("1234567890");
    }

    @Test
    void tokenMaskShortToken() {
        StoreBot bot = StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "ab",
                "h", "s", "sh", "Bot", NOW);

        assertThat(bot.tokenMask()).isEqualTo("****");
    }

    @Test
    void activateFromRegisteringMovesToActive() {
        StoreBot bot = telegramBot();

        bot.activate(NOW);

        assertThat(bot.status()).isEqualTo(BotStatus.ACTIVE);
        assertThat(bot.errorMessage()).isNull();
        assertThat(bot.lastHealthCheckAt()).isEqualTo(NOW);
    }

    @Test
    void pauseAndResumeCycle() {
        StoreBot bot = telegramBot();
        bot.activate(NOW);
        bot.pause(NOW.plusSeconds(10));

        assertThat(bot.status()).isEqualTo(BotStatus.PAUSED);

        bot.resume(NOW.plusSeconds(20));

        assertThat(bot.status()).isEqualTo(BotStatus.ACTIVE);
        assertThat(bot.errorMessage()).isNull();
    }

    @Test
    void pauseFromNonActiveThrows409() {
        StoreBot bot = telegramBot();

        assertThatThrownBy(() -> bot.pause(NOW)).isInstanceOf(InvalidBotStateException.class);
    }

    @Test
    void resumeFromNonPausedThrows409() {
        StoreBot bot = telegramBot();
        bot.activate(NOW);

        assertThatThrownBy(() -> bot.resume(NOW)).isInstanceOf(InvalidBotStateException.class);
    }

    @Test
    void markErrorMovesToErrorAndKeepsMessage() {
        StoreBot bot = telegramBot();
        bot.activate(NOW);

        bot.markError("Falha ao registrar webhook: 401", NOW.plusSeconds(5));

        assertThat(bot.status()).isEqualTo(BotStatus.ERROR);
        assertThat(bot.errorMessage()).contains("webhook");
    }

    @Test
    void markErrorFromRegisteringIsAllowed() {
        StoreBot bot = telegramBot();

        bot.markError("getMe falhou", NOW);

        assertThat(bot.status()).isEqualTo(BotStatus.ERROR);
    }

    @Test
    void activateFromErrorReintegrates() {
        StoreBot bot = telegramBot();
        bot.markError("token invalido", NOW);

        bot.activate(NOW.plusSeconds(1));

        assertThat(bot.status()).isEqualTo(BotStatus.ACTIVE);
        assertThat(bot.errorMessage()).isNull();
    }

    @Test
    void markErrorTruncatesLongMessage() {
        StoreBot bot = telegramBot();

        bot.markError("x".repeat(1000), NOW);

        assertThat(bot.errorMessage()).hasSize(500);
    }

    @Test
    void removeIsSoftDelete() {
        StoreBot bot = telegramBot();

        bot.remove(NOW);

        assertThat(bot.isDeleted()).isTrue();
        assertThat(bot.deletedAt()).isEqualTo(NOW);
        assertThat(bot.status()).isEqualTo(BotStatus.REGISTERING); // status preservado p/ trilha
    }

    @Test
    void removeIsIdempotent() {
        StoreBot bot = telegramBot();
        bot.remove(NOW);
        bot.remove(NOW.plusSeconds(1));

        assertThat(bot.deletedAt()).isEqualTo(NOW);
    }

    @Test
    void deletedBotCannotBeActivated() {
        StoreBot bot = telegramBot();
        bot.remove(NOW);

        assertThatThrownBy(() -> bot.activate(NOW)).isInstanceOf(InvalidBotStateException.class);
    }

    @Test
    void updateDisplayNameChangesAndBumpsUpdatedAt() {
        StoreBot bot = telegramBot();

        bot.updateDisplayName("Novo Nome", NOW.plusSeconds(30));

        assertThat(bot.displayName()).isEqualTo("Novo Nome");
        assertThat(bot.updatedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void updateDisplayNameRejectsBlank() {
        StoreBot bot = telegramBot();

        assertThatThrownBy(() -> bot.updateDisplayName("   ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setIdentitySetsUsernameAndDisplayName() {
        StoreBot bot = telegramBot();

        bot.setIdentity("vendasbot", "Bot Vendas VIP", NOW);

        assertThat(bot.username()).isEqualTo("vendasbot");
        assertThat(bot.displayName()).isEqualTo("Bot Vendas VIP");
    }

    @Test
    void setApplicationIdWorksForDiscord() {
        StoreBot bot = telegramBot();

        bot.setApplicationId("app-123", NOW);

        assertThat(bot.applicationId()).isEqualTo("app-123");
    }

    @Test
    void setWebhookSecretUpdatesHash() {
        StoreBot bot = telegramBot();

        bot.setWebhookSecret("novo-segredo-64-chars...", "novo-hash", NOW);

        assertThat(bot.webhookSecret()).isEqualTo("novo-segredo-64-chars...");
        assertThat(bot.webhookSecretHash()).isEqualTo("novo-hash");
    }

    @Test
    void createAcceptsIndependentSecretToken() {
        StoreBot bot = StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "tok", "hash",
                "wh-secret", "wh-secret-hash", "wh-token", "wh-token-hash", "Bot", NOW);

        assertThat(bot.webhookSecret()).isEqualTo("wh-secret");
        assertThat(bot.webhookSecretToken()).isEqualTo("wh-token");
        assertThat(bot.webhookSecretTokenHash()).isEqualTo("wh-token-hash");
    }

    @Test
    void createRejectsTokenHashWithoutToken() {
        assertThatThrownBy(() -> StoreBot.create(MERCHANT_ID, BotPlatform.TELEGRAM, "tok", "h",
                "s", "sh", null, "th", "Bot", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setWebhookSecretTokenUpdatesHash() {
        StoreBot bot = telegramBot();

        bot.setWebhookSecretToken("novo-token-64-chars", "novo-token-hash", NOW);

        assertThat(bot.webhookSecretToken()).isEqualTo("novo-token-64-chars");
        assertThat(bot.webhookSecretTokenHash()).isEqualTo("novo-token-hash");
        assertThatThrownBy(() -> bot.setWebhookSecretToken(" ", "h", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bot.setWebhookSecretToken("t", " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreKeepsAllFieldsAndNullWebhookSecret() {
        UUID id = UUID.randomUUID();
        StoreBot bot = StoreBot.restore(id, MERCHANT_ID, BotPlatform.DISCORD, "discord-token",
                "token-hash", "wh-hash", "Bot D", "meubot", "app-1", BotStatus.ACTIVE,
                null, NOW, null, NOW, NOW);

        assertThat(bot.id()).isEqualTo(id);
        assertThat(bot.platform()).isEqualTo(BotPlatform.DISCORD);
        assertThat(bot.token()).isEqualTo("discord-token");
        assertThat(bot.status()).isEqualTo(BotStatus.ACTIVE);
        assertThat(bot.username()).isEqualTo("meubot");
        assertThat(bot.applicationId()).isEqualTo("app-1");
        assertThat(bot.webhookSecret()).isNull(); // só o hash é persistido
        assertThat(bot.webhookSecretHash()).isEqualTo("wh-hash");
    }
}
