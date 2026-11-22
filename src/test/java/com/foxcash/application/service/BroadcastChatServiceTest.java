package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TelegramBotApi;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do destino dinâmico do broadcast (Spec 79/D5): delega por plataforma
 * (Telegram getUpdates/getChat; Discord guilds), escopa por lojista (404
 * cross-lojista) e usa cache curto (TTL ~60s — a plataforma é chamada 1×).
 */
class BroadcastChatServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();

    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final TelegramBotApi telegramApi = mock(TelegramBotApi.class);
    private final DiscordBotApi discordApi = mock(DiscordBotApi.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private BroadcastChatService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastChatService(botRepository, telegramApi, discordApi, clock);
    }

    private StoreBot telegramBot() {
        return StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM,
                "123456:ABC-secret-token", "hash", "wh-hash", "Bot Vendas", "vendasbot", null,
                BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
    }

    @Test
    void telegramListsChatsFromIntegration() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        when(telegramApi.listChats("123456:ABC-secret-token"))
                .thenReturn(List.of(new TelegramBotApi.ChatInfo("-1001", "Canal de Ofertas", "channel")));

        List<BroadcastChatService.Chat> chats = service.listChats(MERCHANT_ID, BOT_ID);

        assertThat(chats).hasSize(1);
        assertThat(chats.get(0).id()).isEqualTo("-1001");
        assertThat(chats.get(0).title()).isEqualTo("Canal de Ofertas");
        assertThat(chats.get(0).type()).isEqualTo("channel");
    }

    @Test
    void discordListsGuilds() {
        StoreBot discordBot = StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.DISCORD,
                "discord-token", "hash", "wh-hash", "Bot Discord", null, "123456789",
                BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(discordBot));
        when(discordApi.listGuilds("discord-token"))
                .thenReturn(List.of(new DiscordBotApi.ChatInfo("123456", "Meu Servidor")));

        List<BroadcastChatService.Chat> chats = service.listChats(MERCHANT_ID, BOT_ID);

        assertThat(chats).hasSize(1);
        assertThat(chats.get(0).id()).isEqualTo("123456");
        assertThat(chats.get(0).title()).isEqualTo("Meu Servidor");
        assertThat(chats.get(0).type()).isEqualTo("guild");
    }

    @Test
    void cachesByBotWithinTtl() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.of(telegramBot()));
        when(telegramApi.listChats("123456:ABC-secret-token"))
                .thenReturn(List.of(new TelegramBotApi.ChatInfo("-1001", "Canal", "channel")));

        service.listChats(MERCHANT_ID, BOT_ID);
        service.listChats(MERCHANT_ID, BOT_ID);

        // Segunda chamada dentro do TTL → não martela a API da plataforma.
        verify(telegramApi, times(1)).listChats("123456:ABC-secret-token");
    }

    @Test
    void botOfAnotherMerchantThrows404() {
        when(botRepository.findByIdAndMerchantId(BOT_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listChats(MERCHANT_ID, BOT_ID))
                .isInstanceOf(StoreBotNotFoundException.class);
    }
}
