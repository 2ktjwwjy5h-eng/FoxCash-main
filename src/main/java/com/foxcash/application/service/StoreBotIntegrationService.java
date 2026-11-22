package com.foxcash.application.service;

import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.domain.port.out.TelegramBotApi;
import com.foxcash.infrastructure.config.BotProperties;
import com.foxcash.infrastructure.security.Sha256Hasher;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Orquestra a integração dos bots de venda por plataforma (Spec 74/D4):
 *
 * <ul>
 *   <li><b>Telegram</b> — {@code integrate}: {@code getMe} (grava nome/username)
 *       + {@code setWebhook} com URL {@code https://<host>/webhooks/telegram/
 *       {id}/{secret}} (secret 32 bytes, nunca o id) e {@code secret_token};
 *       {@code disintegrate}: {@code deleteWebhook} (falha de rede é logada e
 *       re-tentada pelo job de reconciliação — o webhook público descarta
 *       updates em PAUSED/ERROR de qualquer forma).</li>
 *   <li><b>Discord</b> — {@code integrate}: gerenciador de conexão (valida o
 *       token + registra comandos slash); {@code disintegrate}: encerra a
 *       conexão em memória.</li>
 * </ul>
 *
 * <p><b>Reconciliação</b> ({@link #reconcile()} — job ShedLock + Spec 53):
 * {@code REGISTERING} preso → {@code ERROR}; {@code ACTIVE} sem health recente
 * → re-verifica (re-integra); {@code ERROR} → tenta re-integrar. Métricas
 * {@code foxcash.bot.*}.
 */
@Service
public class StoreBotIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(StoreBotIntegrationService.class);

    /** URL base de dev quando {@code webhook-public-base-url} está vazio (Spec 74/D10). */
    static final String DEV_WEBHOOK_BASE_URL = "http://localhost:8080";

    private final TelegramBotApi telegramApi;
    private final DiscordBotManager discordBotManager;
    private final StoreBotRepository botRepository;
    private final BotProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public StoreBotIntegrationService(TelegramBotApi telegramApi,
                                      DiscordBotManager discordBotManager,
                                      StoreBotRepository botRepository,
                                      BotProperties properties,
                                      Clock clock,
                                      MeterRegistry meterRegistry) {
        this.telegramApi = telegramApi;
        this.discordBotManager = discordBotManager;
        this.botRepository = botRepository;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** Integra o bot na plataforma (Telegram: getMe + setWebhook; Discord: conexão). */
    public void integrate(StoreBot bot) {
        Instant now = clock.instant();
        if (bot.platform() == BotPlatform.TELEGRAM) {
            integrateTelegram(bot, now);
        } else {
            integrateDiscord(bot, now);
        }
    }

    /** Desintegra o bot (Telegram: deleteWebhook; Discord: destroy) — best-effort. */
    public void disintegrate(StoreBot bot) {
        if (bot.platform() == BotPlatform.TELEGRAM) {
            disintegrateTelegram(bot);
        } else {
            discordBotManager.disconnect(bot.id());
        }
    }

    /**
     * Spec 77 (wizard): valida o token do Telegram via {@code getMe} SEM criar
     * registro — devolve a identidade (nome/@) para o passo de validação em
     * tempo real. Token inválido/expirado →
     * {@link com.foxcash.domain.exception.TelegramApiException}.
     */
    public TelegramBotApi.TelegramUserInfo validateToken(String token) {
        return telegramApi.getMe(token);
    }

    /**
     * Spec 77 (wizard): aplica a configuração inicial opcional do bot no
     * Telegram (foto/descrição/descrição curta) — stateless no servidor (nada
     * é gravado). Campos ausentes/vazios são pulados; falha em qualquer chamada
     * → {@link com.foxcash.domain.exception.TelegramApiException} (400 na API).
     */
    public void applyTelegramSetup(String token, String description, String shortDescription,
                                   byte[] photo, String photoFilename) {
        if (photo != null && photo.length > 0) {
            telegramApi.setMyPhoto(token, photo, photoFilename == null ? "bot-photo.jpg" : photoFilename);
        }
        if (description != null && !description.isBlank()) {
            telegramApi.setMyDescription(token, description);
        }
        if (shortDescription != null && !shortDescription.isBlank()) {
            telegramApi.setMyShortDescription(token, shortDescription);
        }
    }

    /**
     * Spec 78 (wizard): valida o token do Discord via {@code getMe} SEM criar
     * registro — devolve a identidade (username) para a verificação em tempo
     * real. Token inválido/expirado → {@code DiscordApiException}.
     */
    public DiscordBotApi.DiscordUserInfo validateDiscordToken(String token) {
        return discordBotManager.validate(token);
    }

    /**
     * Spec 78 (wizard): aplica a configuração inicial opcional do bot no Discord
     * (avatar/descrição via {@code PATCH /users/@me} + comandos slash) —
     * stateless no servidor (nada é gravado). Campos ausentes são pulados; falha
     * em qualquer chamada → {@code DiscordApiException} (400 na API).
     */
    public void applyDiscordSetup(String token, String applicationId, String description, byte[] avatar) {
        discordBotManager.applySetup(token, applicationId, description, avatar);
    }

    // ---- Integração por plataforma ----

    private void integrateTelegram(StoreBot bot, Instant now) {
        TelegramBotApi.TelegramUserInfo me = telegramApi.getMe(bot.token());
        bot.setIdentity(me.username(), me.displayName(), now);

        // Após um reload do banco (só os hashes são persistidos) os valores em
        // claro são null — gera novos e re-registra o webhook com a URL nova.
        String secret = bot.webhookSecret();
        if (secret == null) {
            secret = WebhookSecretGenerator.generate();
            bot.setWebhookSecret(secret, Sha256Hasher.hex(secret), now);
        }
        String secretToken = bot.webhookSecretToken();
        if (secretToken == null) {
            secretToken = WebhookSecretGenerator.generate();
            bot.setWebhookSecretToken(secretToken, Sha256Hasher.hex(secretToken), now);
        }
        // Auditoria 2026-08-18: o secret_token do header é INDEPENDENTE do
        // segredo da URL — vazar a URL não entrega o header.
        String url = webhookBaseUrl() + "/webhooks/telegram/" + bot.id() + "/" + secret;
        telegramApi.setWebhook(bot.token(), url, secretToken);
        bot.refreshHealth(now);
    }

    private void integrateDiscord(StoreBot bot, Instant now) {
        DiscordBotApi.DiscordUserInfo info = discordBotManager.connect(bot.id(), bot.token(), bot.applicationId());
        if (info != null) {
            bot.setIdentity(info.username(), info.displayName(), now);
        }
        bot.refreshHealth(now);
    }

    private void disintegrateTelegram(StoreBot bot) {
        try {
            telegramApi.deleteWebhook(bot.token());
        } catch (Exception e) {
            // Falha de rede no deleteWebhook: loga (o webhook continua ativo no
            // Telegram, mas o endpoint público descarta updates em PAUSED/ERROR —
            // e o resume re-registra com URL nova). Re-tentativa coberta pelo job.
            log.warn("Falha ao remover webhook do bot {}: {}", bot.id(), e.getMessage());
        }
    }

    // ---- Reconciliação (job ShedLock — Spec 53) ----

    /**
     * Reconcilia o registro contra a realidade da plataforma: REGISTERING preso
     * → ERROR; ACTIVE sem health recente → re-verifica (re-integra); ERROR → tenta
     * re-integrar (token válido). PAUSED/deleted são ignorados (integração
     * desativada por design).
     */
    public void reconcile() {
        if (!properties.isReconcileEnabled()) {
            log.info("Reconciliacao de bots desligada (foxcash.bots.reconcile-enabled=false)");
            return;
        }
        Instant now = clock.instant();
        Duration registeringTimeout = Duration.ofMinutes(properties.getRegisteringTimeoutMinutes());
        Duration healthTimeout = Duration.ofMinutes(properties.getHealthTimeoutMinutes());

        for (StoreBot bot : botRepository.findAllNonDeleted()) {
            try {
                reconcileOne(bot, now, registeringTimeout, healthTimeout);
            } catch (Exception e) {
                log.warn("Falha na reconciliacao do bot {}: {}", bot.id(), e.getMessage());
            }
        }
    }

    private void reconcileOne(StoreBot bot, Instant now, Duration registeringTimeout, Duration healthTimeout) {
        switch (bot.status()) {
            case REGISTERING -> {
                if (bot.createdAt().isBefore(now.minus(registeringTimeout))) {
                    bot.markError("Registro preso em REGISTERING (timeout)", now);
                    botRepository.save(bot);
                    reconcileCounter("error");
                }
            }
            case ACTIVE -> {
                boolean staleHealth = bot.lastHealthCheckAt() == null
                        || bot.lastHealthCheckAt().isBefore(now.minus(healthTimeout));
                if (staleHealth) {
                    reVerify(bot, now);
                }
            }
            case ERROR -> reVerify(bot, now);
            default -> { /* PAUSED: integração desativada — nada a fazer */ }
        }
    }

    /** Re-integra o bot; sucesso → ACTIVE, falha → mantém/marca ERROR. */
    private void reVerify(StoreBot bot, Instant now) {
        try {
            integrate(bot);
            bot.activate(now);
            botRepository.save(bot);
            reconcileCounter("ok");
        } catch (Exception e) {
            bot.markError(errorMessage(e), now);
            botRepository.save(bot);
            reconcileCounter("error");
        }
    }

    private void reconcileCounter(String state) {
        meterRegistry.counter("foxcash.bot.reconcile", "state", state).increment();
    }

    private String webhookBaseUrl() {
        String base = properties.getWebhookPublicBaseUrl();
        if (base == null || base.isBlank()) {
            return DEV_WEBHOOK_BASE_URL;
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
