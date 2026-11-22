package com.foxcash.infrastructure.inbound.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.BotLeadService;
import com.foxcash.application.service.CrmLeadService;
import com.foxcash.application.service.WebhookEventPublisher;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.RateLimitStore;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.security.ClientIpResolver;
import com.foxcash.infrastructure.security.InMemoryRateLimitStore;
import com.foxcash.infrastructure.security.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do endpoint público de updates do Telegram (Spec 74/D5.3): segredo
 * inválido → 404 silencioso, header secret_token OBRIGATÓRIO e independente da
 * URL (auditoria 2026-08-18 — ausente/inválido → 404), bot PAUSED/ERROR →
 * descarta (204, nunca encaminha), bot ACTIVE → publica
 * {@code TELEGRAM_UPDATE} no outbox e rate limit por IP → 429.
 */
class TelegramWebhookControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String SECRET = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
    private static final String TOKEN = "f1e2d3c4b5a697887766554433221100ffeeddccbbaa9988776655443322110";
    private static final String TOKEN_HASH = com.foxcash.infrastructure.security.Sha256Hasher.hex(TOKEN);

    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final WebhookEventPublisher eventPublisher = mock(WebhookEventPublisher.class);
    private final BotLeadRepository botLeadRepository = mock(BotLeadRepository.class);
    private final CrmLeadService crmLeadService = mock(CrmLeadService.class);
    private final RateLimitProperties rateLimitProperties = new RateLimitProperties();
    private final ClientIpResolver clientIpResolver = new ClientIpResolver(false);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private TelegramWebhookController controller;

    @BeforeEach
    void setUp() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        rateLimitProperties.getWebhook().setEnabled(false);
        controller = new TelegramWebhookController(botRepository, eventPublisher, mock(BotLeadService.class),
                botLeadRepository, crmLeadService,
                new InMemoryRateLimitStore(java.time.Clock.systemUTC()), rateLimitProperties,
                clientIpResolver, new ObjectMapper());
    }

    private StoreBot bot(BotStatus status) {
        return StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM, "123456:ABC-token",
                "hash", com.foxcash.infrastructure.security.Sha256Hasher.hex(SECRET), TOKEN_HASH,
                "Bot Vendas", "vendasbot", null, status, null, NOW, null, NOW, NOW);
    }

    /** Bot legado (V79): token hash == hash do url_secret (header ainda é o url_secret). */
    private StoreBot legacyBot(BotStatus status) {
        return StoreBot.restore(BOT_ID, MERCHANT_ID, BotPlatform.TELEGRAM, "123456:ABC-token",
                "hash", com.foxcash.infrastructure.security.Sha256Hasher.hex(SECRET),
                com.foxcash.infrastructure.security.Sha256Hasher.hex(SECRET),
                "Bot Vendas", "vendasbot", null, status, null, NOW, null, NOW, NOW);
    }

    @Test
    void validSecretAndActiveBotPublishesToOutbox() {
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.ACTIVE)));

        var response = controller.receive(BOT_ID, SECRET, TOKEN, "{\"update_id\":1}", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(eventPublisher).publish(eq(MERCHANT_ID), eq(WebhookEventType.TELEGRAM_UPDATE), any(Map.class));
    }

    @Test
    void invalidSecretReturns404Silently() {
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.empty());

        var response = controller.receive(BOT_ID, "wrong-secret", TOKEN, "{}", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void missingHeaderSecretTokenReturns404() {
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.ACTIVE)));

        var response = controller.receive(BOT_ID, SECRET, null, "{}", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void mismatchedHeaderSecretTokenReturns404() {
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.ACTIVE)));

        var response = controller.receive(BOT_ID, SECRET, "header-diferente", "{}", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void urlSecretDoesNotPassAsHeaderToken() {
        // O segredo da URL NÃO vale como header — são tokens independentes.
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.ACTIVE)));

        var response = controller.receive(BOT_ID, SECRET, SECRET, "{}", request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void legacyBotAcceptsUrlSecretAsHeaderToken() {
        // V79: bots legados têm token hash == hash(url_secret) — sem break.
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(legacyBot(BotStatus.ACTIVE)));

        var response = controller.receive(BOT_ID, SECRET, SECRET, "{\"update_id\":1}", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(eventPublisher).publish(eq(MERCHANT_ID), eq(WebhookEventType.TELEGRAM_UPDATE), any(Map.class));
    }

    @Test
    void pausedBotDiscardsWithoutForwarding() {
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.PAUSED)));

        var response = controller.receive(BOT_ID, SECRET, TOKEN, "{}", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void errorBotDiscardsWithoutForwarding() {
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.ERROR)));

        var response = controller.receive(BOT_ID, SECRET, TOKEN, "{}", request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void rateLimitByIpReturns429() {
        rateLimitProperties.getWebhook().setEnabled(true);
        rateLimitProperties.getWebhook().setMaxRequests(1);
        RateLimitStore store = new InMemoryRateLimitStore(java.time.Clock.systemUTC());
        TelegramWebhookController limited = new TelegramWebhookController(botRepository, eventPublisher,
                mock(BotLeadService.class), botLeadRepository, crmLeadService,
                store, rateLimitProperties, clientIpResolver, new ObjectMapper());
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.ACTIVE)));

        // 1ª requisição passa; 2ª (mesma janela) → 429 antes do lookup.
        var first = limited.receive(BOT_ID, SECRET, TOKEN, "{}", request);
        var second = limited.receive(BOT_ID, SECRET, TOKEN, "{}", request);

        assertThat(first.getStatusCode().value()).isEqualTo(204);
        assertThat(second.getStatusCode().value()).isEqualTo(429);
    }

    // ---- Spec 90: handoff humano (lead em HUMAN → consome para o CRM, não publica) ----

    private static final String PRIVATE_MSG = "{\"update_id\":2,\"message\":{\"message_id\":1,"
            + "\"from\":{\"id\":123},\"chat\":{\"id\":123,\"type\":\"private\"},\"text\":\"oi\"}}";

    @Test
    void humanHandoffLeadConsumesWithoutPublishing() {
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.ACTIVE)));
        when(botLeadRepository.findByBotIdAndPlatformUserId(eq(BOT_ID), eq("123")))
                .thenReturn(Optional.of(BotLead.create(BOT_ID, "123", NOW).pauseBot()));

        var response = controller.receive(BOT_ID, SECRET, TOKEN, PRIVATE_MSG, request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        // Funil abortado: NUNCA publica TELEGRAM_UPDATE; mensagem vai para o CRM.
        verify(eventPublisher, never()).publish(any(), any(), any());
        verify(crmLeadService).persistInbound(eq(MERCHANT_ID), eq(BOT_ID), eq("123"), eq("oi"), isNull());
    }

    @Test
    void botHandoffLeadStillPublishes() {
        when(botRepository.findByWebhookSecretHash(any())).thenReturn(Optional.of(bot(BotStatus.ACTIVE)));
        when(botLeadRepository.findByBotIdAndPlatformUserId(eq(BOT_ID), eq("123")))
                .thenReturn(Optional.of(BotLead.create(BOT_ID, "123", NOW)));

        var response = controller.receive(BOT_ID, SECRET, TOKEN, PRIVATE_MSG, request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(eventPublisher).publish(eq(MERCHANT_ID), eq(WebhookEventType.TELEGRAM_UPDATE), any(Map.class));
        verify(crmLeadService, never()).persistInbound(any(), any(), any(), any(), any());
    }
}
