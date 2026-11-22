package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TelegramBotApi;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aplicação: destino dinâmico do broadcast (Spec 79/D5) — canais/grupos onde o
 * bot é admin, carregados da plataforma via integração da Spec 74:
 * {@code GET /api/v1/portal/bots/{id}/chats}.
 *
 * <p><b>Cache curto (TTL ~60s)</b>: para não martelar a API da plataforma a
 * cada requisição do editor (Spec 80). Por bot; expira sozinho (nunca cresce
 * sem limite).
 */
@Service
public class BroadcastChatService {

    /** TTL do cache de chats por bot (D5). */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final StoreBotRepository botRepository;
    private final TelegramBotApi telegramApi;
    private final DiscordBotApi discordApi;
    private final Clock clock;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public BroadcastChatService(StoreBotRepository botRepository,
                                TelegramBotApi telegramApi,
                                DiscordBotApi discordApi,
                                Clock clock) {
        this.botRepository = botRepository;
        this.telegramApi = telegramApi;
        this.discordApi = discordApi;
        this.clock = clock;
    }

    /**
     * Canais/grupos/guilds onde o bot tem permissão (destino do broadcast).
     *
     * @return lista {@code [{id, title, type}]} — {@code type} é
     *         channel/group/supergroup (Telegram) ou guild (Discord).
     */
    public List<Chat> listChats(UUID merchantId, UUID botId) {
        StoreBot bot = botRepository.findByIdAndMerchantId(botId, merchantId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(StoreBotNotFoundException::new);
        CacheEntry entry = cache.get(botId);
        Instant now = clock.instant();
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return entry.chats();
        }
        List<Chat> chats = resolve(bot);
        cache.put(botId, new CacheEntry(chats, now.plus(CACHE_TTL)));
        return chats;
    }

    private List<Chat> resolve(StoreBot bot) {
        if (bot.platform() == BotPlatform.TELEGRAM) {
            return telegramApi.listChats(bot.token()).stream()
                    .map(chat -> new Chat(chat.id(), chat.title(), chat.type()))
                    .toList();
        }
        return discordApi.listGuilds(bot.token()).stream()
                .map(guild -> new Chat(guild.id(), guild.title(), "guild"))
                .toList();
    }

    /** Chat de destino do broadcast (id/title/type por plataforma). */
    public record Chat(String id, String title, String type) {
    }

    private record CacheEntry(List<Chat> chats, Instant expiresAt) {
    }
}
