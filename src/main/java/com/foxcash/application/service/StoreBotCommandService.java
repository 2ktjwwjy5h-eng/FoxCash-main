package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreBotAlreadyExistsException;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.in.StoreBotManagementUseCase;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.security.Sha256Hasher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: gestão do registro de bots de venda pelo portal (Spec 74) — tudo
 * por {@code merchantId} do principal (404 {@code bot_not_found} cross-lojista).
 *
 * <p>O registro cria o bot ({@code REGISTERING}) e dispara a integração da
 * plataforma — sucesso → {@code ACTIVE}, falha → {@code ERROR} (auditoria
 * {@code BOT_INTEGRATION_ERROR}); pausar/retomar/remover também
 * desativa/re-ativa a integração. Auditoria Spec 41 na mesma transação; o token
 * nunca vai em claro para a trilha (só máscara/identidade).
 */
@Service
public class StoreBotCommandService implements StoreBotManagementUseCase {

    private final StoreBotRepository botRepository;
    private final StoreBotIntegrationService integrationService;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public StoreBotCommandService(StoreBotRepository botRepository,
                                  StoreBotIntegrationService integrationService,
                                  PlatformActivityRecorder activityRecorder,
                                  Clock clock) {
        this.botRepository = botRepository;
        this.integrationService = integrationService;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public StoreBot get(UUID merchantId, UUID botId) {
        return requireBot(merchantId, botId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreBot> list(UUID merchantId, BotPlatform platform, BotStatus status) {
        return list(merchantId, null, platform, status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreBot> list(UUID merchantId, UUID profileId, BotPlatform platform, BotStatus status) {
        // Spec 124 (frota do perfil): profileId null → todos os bots do lojista
        // (regressão — sem header); presente → só store_bots.profile_id = perfil
        // (perfil de outro lojista → vazio — a query é merchant-scoped).
        List<StoreBot> bots = profileId == null
                ? botRepository.findByMerchantId(merchantId)
                : botRepository.findByMerchantIdAndProfileId(merchantId, profileId);
        if (platform != null) {
            bots = bots.stream().filter(b -> b.platform() == platform).toList();
        }
        if (status != null) {
            bots = bots.stream().filter(b -> b.status() == status).toList();
        }
        return bots;
    }

    @Override
    @Transactional
    public StoreBot register(UUID merchantId, RegisterBotCommand command) {
        BotPlatform platform = requirePlatform(command);
        String token = requireToken(command.token());
        Instant now = clock.instant();

        // token_hash UNIQUE no banco — checa antes para devolver 409 (lookup por
        // hash determinístico; o ciphertext AES-GCM não permite busca).
        String tokenHash = Sha256Hasher.hex(token);
        if (botRepository.findByTokenHash(tokenHash).isPresent()) {
            throw new StoreBotAlreadyExistsException();
        }

        String webhookSecret = WebhookSecretGenerator.generate();
        String webhookSecretToken = WebhookSecretGenerator.generate();
        StoreBot bot = StoreBot.create(merchantId, platform, token, tokenHash,
                webhookSecret, Sha256Hasher.hex(webhookSecret),
                webhookSecretToken, Sha256Hasher.hex(webhookSecretToken),
                command.displayName(), now);
        if (platform == BotPlatform.DISCORD && command.applicationId() != null
                && !command.applicationId().isBlank()) {
            bot.setApplicationId(command.applicationId(), now);
        }
        try {
            bot = botRepository.save(bot);
        } catch (DataIntegrityViolationException e) {
            // Replay concorrente do MESMO token (o check acima viu o banco vazio,
            // mas outra requisição inseriu antes do nosso flush): a UNIQUE de
            // token_hash serializa — 409 em vez de 500 (padrão Spec 49: replay
            // concorrente executa 1×, o outro recebe conflito limpo).
            throw new StoreBotAlreadyExistsException();
        }

        // Integra a plataforma (getMe + setWebhook / conexão). Falha → ERROR com
        // auditoria (o registro permanece — trilha do ciclo de vida).
        try {
            integrationService.integrate(bot);
            bot.activate(clock.instant());
        } catch (Exception e) {
            bot.markError(shortMessage(e), clock.instant());
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                    PlatformAction.BOT_INTEGRATION_ERROR, "STORE_BOT", bot.id(), merchantId,
                    Map.of("platform", platform, "error", shortMessage(e)));
        }
        StoreBot saved = botRepository.save(bot);
        // LinkedHashMap: permite valores nulos (username pode ficar null se a
        // integração não retornar identidade — Map.of lançaria NPE).
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("platform", platform);
        details.put("displayName", saved.displayName());
        details.put("username", saved.username());
        details.put("status", saved.status().name());
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BOT_REGISTERED, "STORE_BOT", saved.id(), merchantId, details);
        return saved;
    }

    @Override
    @Transactional
    public StoreBot update(UUID merchantId, UUID botId, String displayName) {
        StoreBot bot = requireBot(merchantId, botId);
        bot.updateDisplayName(displayName, clock.instant());
        StoreBot saved = botRepository.save(bot);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BOT_UPDATED, "STORE_BOT", saved.id(), merchantId,
                Map.of("platform", saved.platform(), "displayName", saved.displayName()));
        return saved;
    }

    @Override
    @Transactional
    public StoreBot pause(UUID merchantId, UUID botId) {
        StoreBot bot = requireBot(merchantId, botId);
        bot.pause(clock.instant());
        // Desintegra (deleteWebhook/destroy) — best-effort, nunca lança.
        integrationService.disintegrate(bot);
        StoreBot saved = botRepository.save(bot);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BOT_PAUSED, "STORE_BOT", saved.id(), merchantId,
                Map.of("platform", saved.platform(), "status", saved.status().name()));
        return saved;
    }

    @Override
    @Transactional
    public StoreBot resume(UUID merchantId, UUID botId) {
        StoreBot bot = requireBot(merchantId, botId);
        bot.resume(clock.instant());
        try {
            integrationService.integrate(bot);
        } catch (Exception e) {
            bot.markError(shortMessage(e), clock.instant());
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                    PlatformAction.BOT_INTEGRATION_ERROR, "STORE_BOT", bot.id(), merchantId,
                    Map.of("platform", bot.platform(), "error", shortMessage(e)));
        }
        StoreBot saved = botRepository.save(bot);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BOT_RESUMED, "STORE_BOT", saved.id(), merchantId,
                Map.of("platform", saved.platform(), "status", saved.status().name()));
        return saved;
    }

    @Override
    @Transactional
    public void remove(UUID merchantId, UUID botId) {
        StoreBot bot = requireBot(merchantId, botId);
        // Desintegra primeiro (deleteWebhook/destroy) — best-effort, nunca lança.
        integrationService.disintegrate(bot);
        bot.remove(clock.instant());
        botRepository.save(bot);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BOT_REMOVED, "STORE_BOT", botId, merchantId,
                Map.of("platform", bot.platform(), "displayName", bot.displayName()));
    }

    @Override
    public com.foxcash.domain.port.out.TelegramBotApi.TelegramUserInfo validateToken(String token) {
        // Spec 77 (wizard): pré-validação em tempo real — stateless, nada é gravado.
        // Token inválido/expirado → TelegramApiException (400 na API).
        return integrationService.validateToken(requireToken(token));
    }

    @Override
    public void applyTelegramSetup(TelegramSetupCommand command) {
        // Spec 77 (wizard): configuração inicial opcional (foto/descrição) — sem
        // registro no FoxCash até o passo final (POST /portal/bots).
        integrationService.applyTelegramSetup(requireToken(command.token()),
                command.description(), command.shortDescription(),
                command.photo(), command.photoFilename());
    }

    @Override
    public com.foxcash.domain.port.out.DiscordBotApi.DiscordUserInfo validateDiscordToken(String token) {
        // Spec 78 (wizard): pré-validação em tempo real — stateless, nada é gravado.
        // Token inválido/expirado → DiscordApiException (400 na API).
        return integrationService.validateDiscordToken(requireToken(token));
    }

    @Override
    public void applyDiscordSetup(DiscordSetupCommand command) {
        // Spec 78 (wizard): configuração inicial opcional (avatar/descrição +
        // comandos slash) — sem registro no FoxCash até o passo final.
        integrationService.applyDiscordSetup(requireToken(command.token()),
                command.applicationId(), command.description(), command.avatar());
    }

    private StoreBot requireBot(UUID merchantId, UUID botId) {
        return botRepository.findByIdAndMerchantId(botId, merchantId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(StoreBotNotFoundException::new);
    }

    private static BotPlatform requirePlatform(RegisterBotCommand command) {
        if (command.platform() == null) {
            throw new IllegalArgumentException("platform e obrigatoria no registro do bot");
        }
        return command.platform();
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token e obrigatorio no registro do bot");
        }
        return token.trim();
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
