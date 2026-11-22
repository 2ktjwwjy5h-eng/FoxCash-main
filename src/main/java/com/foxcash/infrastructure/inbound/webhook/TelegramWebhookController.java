package com.foxcash.infrastructure.inbound.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.BotLeadService;
import com.foxcash.application.service.CrmLeadService;
import com.foxcash.application.service.WebhookEventPublisher;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.botfunnel.UtmParams;
import com.foxcash.domain.model.crm.LeadHandoffStatus;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.RateLimitStore;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import com.foxcash.infrastructure.security.RateLimitProperties;
import com.foxcash.infrastructure.security.Sha256Hasher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint público de updates do Telegram (Spec 74/D5.3) —
 * {@code POST /webhooks/telegram/{id}/{secret}} (fora da auth — mesmo
 * tratamento dos webhooks do PSP, mas com segredo na URL):
 *
 * <ol>
 *   <li>Lookup por {@code webhook_secret_hash} (SHA-256 do segredo da URL) →
 *       não achou → <b>404</b> silencioso (sem processar).</li>
 *   <li>Header {@code X-Telegram-Bot-Api-Secret-Token} <b>obrigatório</b> —
 *       comparação em tempo constante contra o {@code webhook_secret_token_hash}
 *       (token independente da URL — auditoria 2026-08-18). Ausente ou inválido
 *       → <b>404</b>.</li>
 *   <li>Bot {@code PAUSED}/{@code ERROR}/removido → descarta com log (nunca
 *       encaminha) → <b>204</b>.</li>
 *   <li>Publica o evento no <b>outbox</b> (Spec 35) para o renderizador
 *       consumir ({@code TELEGRAM_UPDATE} — mesmo padrão dos eventos de venda
 *       → webhook do bot).</li>
 * </ol>
 *
 * <p>Rate limit por IP (padrão Spec 59/60 — teto generoso, chave normalizada
 * por rota, sem bucket por bot) e body cap (Spec 63 — {@code RequestBodySizeFilter}
 * roda antes com 1 MiB; o body inspection exclui {@code /webhooks/telegram}).
 * Token/secret nunca são logados.
 *
 * <p><b>Migração do secret_token (auditoria 2026-08-18):</b> bots existentes
 * têm o header igual ao segredo da URL (backfill na V79) até a re-integração
 * (register/resume/reconcile) gerar um token independente e re-registrar o
 * webhook. O endpoint sempre compara o hash do header contra o hash
 * persistido — o backfill mantém os bots legados funcionando sem break.
 */
@RestController
@RequestMapping("/webhooks/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private static final String SECRET_TOKEN_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final StoreBotRepository botRepository;
    private final WebhookEventPublisher eventPublisher;
    private final BotLeadService botLeadService;
    private final BotLeadRepository botLeadRepository;
    private final CrmLeadService crmLeadService;
    private final RateLimitStore rateLimitStore;
    private final RateLimitProperties rateLimitProperties;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    public TelegramWebhookController(StoreBotRepository botRepository,
                                     WebhookEventPublisher eventPublisher,
                                     BotLeadService botLeadService,
                                     BotLeadRepository botLeadRepository,
                                     CrmLeadService crmLeadService,
                                     RateLimitStore rateLimitStore,
                                     RateLimitProperties rateLimitProperties,
                                     ClientIpResolver clientIpResolver,
                                     ObjectMapper objectMapper) {
        this.botRepository = botRepository;
        this.eventPublisher = eventPublisher;
        this.botLeadService = botLeadService;
        this.botLeadRepository = botLeadRepository;
        this.crmLeadService = crmLeadService;
        this.rateLimitStore = rateLimitStore;
        this.rateLimitProperties = rateLimitProperties;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{id}/{secret}")
    public ResponseEntity<?> receive(@PathVariable UUID id,
                                        @PathVariable String secret,
                                        @RequestHeader(value = SECRET_TOKEN_HEADER, required = false) String headerSecret,
                                        @RequestBody(required = false) String rawBody,
                                        HttpServletRequest request) {
        // 0) Rate limit por IP (teto generoso — reentrega legítima do Telegram
        // não é afetada). Chave normalizada: TODOS os bots compartilham o bucket
        // por IP (o path tem {id}/{secret} variáveis — sem isso, N bots = N
        // buckets e o teto por IP não significa nada).
        if (rateLimitProperties.isEnabled()
                && rateLimitProperties.getWebhook().isEnabled()) {
            String key = "/webhooks/telegram|" + clientIpResolver.resolve(request);
            if (!rateLimitStore.tryAcquire(key,
                    rateLimitProperties.getWebhook().getMaxRequests(),
                    rateLimitProperties.getWebhook().getWindowSeconds())) {
                log.warn("Webhook do Telegram rejeitado por rate limit (IP={})", clientIpResolver.resolve(request));
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ErrorResponse.of("too_many_requests",
                                "Limite de requisicoes excedido. Tente novamente mais tarde."));
            }
        }

        // 1) Lookup por hash do segredo da URL — 404 silencioso se não existir.
        Optional<StoreBot> found = botRepository.findByWebhookSecretHash(Sha256Hasher.hex(secret));
        if (found.isEmpty() || !found.get().id().equals(id)) {
            log.warn("Webhook do Telegram rejeitado: segredo/rota desconhecida");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        StoreBot bot = found.get();

        // 2) Header secret_token OBRIGATÓRIO (auditoria 2026-08-18) — o Telegram
        // sempre envia quando configurado no setWebhook. Comparação em tempo
        // constante contra o hash do token INDEPENDENTE da URL.
        if (headerSecret == null || headerSecret.isBlank()) {
            log.warn("Webhook do Telegram rejeitado: secret_token do header ausente (bot={})", bot.id());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String tokenHash = bot.webhookSecretTokenHash();
        if (tokenHash == null || !MessageDigest.isEqual(
                Sha256Hasher.hex(headerSecret).getBytes(StandardCharsets.UTF_8),
                tokenHash.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Webhook do Telegram rejeitado: secret_token do header invalido (bot={})", bot.id());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 3) PAUSED/ERROR/removido → descarta (nunca encaminha).
        if (bot.isDeleted() || bot.status() == BotStatus.PAUSED || bot.status() == BotStatus.ERROR) {
            log.info("Webhook do Telegram descartado: bot={} status={} (pausado/erro/removido)", bot.id(), bot.status());
            return ResponseEntity.noContent().build();
        }

        // 4) Spec 75: registra o lead (usuário único que inicia conversa) —
        // idempotente (UNIQUE bot+user); update sem usuário (ex.: channel_post)
        // não é lead (o serviço faz no-op). Spec 81/D4: extrai UTMs do /start e
        // persiste no lead (primeiro valor vence). Em seguida publica no outbox
        // (Spec 35).
        try {
            JsonNode update = rawBody == null || rawBody.isBlank()
                    ? objectMapper.nullNode()
                    : objectMapper.readTree(rawBody);
            String userId = extractPlatformUserId(update);
            botLeadService.recordLead(bot.id(), userId, extractUtmFromStart(update));
            // Spec 90 (handoff humano): se o lead está em HUMAN, a mensagem é
            // consumida para o CRM (persiste CLIENT + push SSE — Spec 83 D7/D8)
            // e o update NÃO é publicado no outbox (o funil do bot não
            // processa). Callbacks (callback_query) também são suprimidos.
            if (userId != null && isHumanHandoff(bot.id(), userId)) {
                crmLeadService.persistInbound(bot.merchantId(), bot.id(), userId,
                        extractText(update), null);
                return ResponseEntity.noContent().build();
            }
            eventPublisher.publish(bot.merchantId(), WebhookEventType.TELEGRAM_UPDATE,
                    Map.of("botId", bot.id().toString(), "update", update));
        } catch (Exception e) {
            log.warn("Falha ao publicar update do Telegram (bot={}): {}", bot.id(), e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    /** Spec 90: o lead existe e está em {@code HUMAN} (bot pausado para ele). */
    private boolean isHumanHandoff(UUID botId, String platformUserId) {
        Optional<BotLead> lead = botLeadRepository.findByBotIdAndPlatformUserId(botId, platformUserId);
        return lead.isPresent() && lead.get().handoffStatus() == LeadHandoffStatus.HUMAN;
    }

    /** Texto da mensagem do update (null para mídia/callback sem texto). */
    private static String extractText(JsonNode update) {
        JsonNode message = update.path("message");
        String text = message.path("text").asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * Spec 81/D4: extrai {@code utm_source/medium/campaign/content/term} do
     * comando {@code /start <payload>} do Telegram (link {@code t.me/bot?start=...}).
     * O payload pode chegar URL-encoded e com {@code &} decodificado pelo Telegram;
     * aqui decodifica e separa por {@code &}. Sem UTM → {@code null}.
     */
    private static UtmParams extractUtmFromStart(JsonNode update) {
        JsonNode message = update.path("message");
        String text = message.path("text").asText(null);
        if (text == null || !text.startsWith("/start")) {
            return null;
        }
        String payload = text.length() > "/start".length() ? text.substring("/start".length()).trim() : "";
        if (payload.isEmpty()) {
            return null;
        }
        // Payload pode estar URL-encoded (ex.: utm_source%3Dfb%26utm_campaign%3Dx).
        try {
            payload = java.net.URLDecoder.decode(payload, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // payload não-encoded — segue como está
        }
        Map<String, String> params = new java.util.HashMap<>();
        for (String pair : payload.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        UtmParams utm = UtmParams.of(
                params.get("utm_source"), params.get("utm_medium"),
                params.get("utm_campaign"), params.get("utm_content"),
                params.get("utm_term"));
        return utm.hasAny() ? utm : null;
    }

    /**
     * Extrai o {@code platform_user_id} do update do Telegram (Spec 75/D6) —
     * o ID do usuário que iniciou a conversa. Caminhos comuns: {@code message},
     * {@code edited_message}, {@code channel_post}, {@code callback_query} e
     * {@code inline_query} (todos têm {@code from.id}). Update sem usuário →
     * {@code null} (não é lead — o serviço faz no-op).
     */
    private static String extractPlatformUserId(JsonNode update) {
        if (update == null || update.isNull()) {
            return null;
        }
        for (String container : new String[]{"message", "edited_message", "channel_post",
                "callback_query", "inline_query", "chosen_inline_result",
                "shipping_query", "pre_checkout_query"}) {
            JsonNode from = update.path(container).path("from").path("id");
            if (from.isNumber()) {
                return from.asText();
            }
        }
        return null;
    }
}
