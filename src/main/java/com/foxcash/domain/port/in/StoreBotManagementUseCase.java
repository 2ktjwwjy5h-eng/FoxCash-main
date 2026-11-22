package com.foxcash.domain.port.in;

import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.domain.port.out.TelegramBotApi;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: gestão do registro de bots de venda pelo portal (Spec 74),
 * {@code ROLE_MERCHANT_PORTAL}. Tudo por {@code merchantId} do principal —
 * nunca por id solto (404 {@code bot_not_found} cross-lojista, padrão IDOR das
 * Specs 24/31).
 *
 * <p>O registro cria o bot ({@code REGISTERING}) e dispara a integração da
 * plataforma (Telegram: getMe + setWebhook; Discord: conexão) — sucesso →
 * {@code ACTIVE}, falha → {@code ERROR} com auditoria. Pausar/retomar/remover
 * também desativa/re-ativa a integração. O token nunca aparece na resposta.
 */
public interface StoreBotManagementUseCase {

    /** Bot do lojista (detalhe do portal). */
    StoreBot get(UUID merchantId, UUID botId);

    /** Todos os bots NÃO removidos do lojista, com filtros opcionais. */
    List<StoreBot> list(UUID merchantId, BotPlatform platform, BotStatus status);

    /**
     * Todos os bots NÃO removidos do lojista com filtros opcionais, escopados
     * por perfil de operação (Spec 124 — frota do perfil). {@code profileId}
     * null → todos os bots do lojista (regressão — sem header); presente → só
     * {@code store_bots.profile_id = :profileId} (perfil de outro lojista →
     * vazio, nunca vaza).
     */
    List<StoreBot> list(UUID merchantId, UUID profileId, BotPlatform platform, BotStatus status);

    /**
     * Cria o registro ({@code REGISTERING}) e integra a plataforma. Token
     * duplicado → 409 {@code bot_already_exists}.
     */
    StoreBot register(UUID merchantId, RegisterBotCommand command);

    /** Edita o nome de exibição (único campo editável nesta spec). */
    StoreBot update(UUID merchantId, UUID botId, String displayName);

    /** {@code ACTIVE → PAUSED} + desintegra (deleteWebhook/destroy). */
    StoreBot pause(UUID merchantId, UUID botId);

    /** {@code PAUSED → ACTIVE} + re-integra (getMe/setWebhook/reconecta). */
    StoreBot resume(UUID merchantId, UUID botId);

    /** Soft-delete + desintegra (204). */
    void remove(UUID merchantId, UUID botId);

    /**
     * Spec 77 (wizard): valida o token do Telegram via {@code getMe} SEM criar
     * registro — devolve a identidade (nome/@) do bot.
     */
    TelegramBotApi.TelegramUserInfo validateToken(String token);

    /**
     * Spec 77 (wizard): aplica a configuração inicial opcional do bot no
     * Telegram (foto/descrição/descrição curta) — SEM gravar nada no FoxCash.
     * Token inválido/expirado → {@code TelegramApiException} (400).
     */
    void applyTelegramSetup(TelegramSetupCommand command);

    /**
     * Spec 78 (wizard): valida o token do Discord via {@code getMe} SEM criar
     * registro — devolve a identidade (username) do bot.
     */
    DiscordBotApi.DiscordUserInfo validateDiscordToken(String token);

    /**
     * Spec 78 (wizard): aplica a configuração inicial opcional do bot no Discord
     * (avatar/descrição + comandos slash) — SEM gravar nada no FoxCash. Token
     * inválido/expirado → {@code DiscordApiException} (400).
     */
    void applyDiscordSetup(DiscordSetupCommand command);

    /** Comando de registro de um bot de venda (token nunca exposto depois). */
    record RegisterBotCommand(BotPlatform platform, String token, String displayName,
                              String applicationId) {
    }

    /** Comando de setup opcional do wizard (Spec 77) — campos todos opcionais. */
    record TelegramSetupCommand(String token, String description, String shortDescription,
                                byte[] photo, String photoFilename) {
    }

    /** Comando de setup opcional do wizard (Spec 78) — campos todos opcionais. */
    record DiscordSetupCommand(String token, String applicationId, String description, byte[] avatar) {
    }
}
