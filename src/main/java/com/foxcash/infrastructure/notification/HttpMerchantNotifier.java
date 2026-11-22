package com.foxcash.infrastructure.notification;

import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.MerchantNotifier;
import com.foxcash.domain.port.out.MerchantWebhookRepository;
import com.foxcash.infrastructure.config.NotificationProperties;
import com.foxcash.infrastructure.security.WebhookDnsResolver;
import com.foxcash.infrastructure.security.WebhookUrlValidator;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Adapter de entrega de notificacoes via webhook de saida assinado (Spec 35).
 *
 * <p>Faz {@code POST} para cada webhook ATIVO do lojista inscrito no evento,
 * com o corpo assinado HMAC-SHA256 (header {@code X-FoxCash-Signature}) e
 * {@code X-FoxCash-Event-Id}/{@code X-FoxCash-Event} para deduplicacao do
 * receptor. Sem webhook ativo -> fallback log (mesmo comportamento do adapter
 * mock da Spec 7). {@code deliver} lanca excecao em falha (2xx/3xx = sucesso;
 * 4xx/5xx/timeout/erro = falha) para o dispatcher agendar retry/backoff.
 *
 * <p><b>SEC-21 (revisão de segurança 2026-08-11):</b> o transporte é o Apache
 * HttpClient 5 com {@link WebhookDnsResolver} PINADO — os endereços validados
 * contra SSRF na resolução são EXATAMENTE os usados na conexão (o java.net.http
 * anterior re-resolvia o DNS no connect, abrindo uma janela de DNS rebinding
 * entre a validação e a conexão). Redirects desabilitados (um 302 para host
 * interno não passaria pela validação).
 */
@Component
@Primary
public class HttpMerchantNotifier implements MerchantNotifier {

    private static final Logger log = LoggerFactory.getLogger(HttpMerchantNotifier.class);

    private static final String SIGNATURE_HEADER = "X-FoxCash-Signature";
    private static final String EVENT_ID_HEADER = "X-FoxCash-Event-Id";
    private static final String EVENT_HEADER = "X-FoxCash-Event";

    /** Teto de leitura do corpo de RESPOSTA (só o status importa; receptor malicioso limitado). */
    private static final int RESPONSE_BODY_LIMIT = 4096;

    private final MerchantWebhookRepository webhookRepository;
    private final NotificationProperties properties;
    private final CloseableHttpClient httpClient;

    public HttpMerchantNotifier(MerchantWebhookRepository webhookRepository,
                                NotificationProperties properties) {
        this.webhookRepository = webhookRepository;
        this.properties = properties;
        this.httpClient = buildHttpClient(properties.isAllowLoopback());
    }

    /** Cliente HTTP com DNS PINADO (SEC-21) e timeouts explícitos. */
    private static CloseableHttpClient buildHttpClient(boolean allowLoopback) {
        // SEC-21: o DnsResolver valida os endereços retornados (públicos, salvo
        // allowLoopback) e o HttpClient5 usa EXATAMENTE esses endereços na
        // conexão — preservando o hostname original no Host header/SNI. Sem
        // segunda resolução independente, o DNS rebinding não tem janela.
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new WebhookDnsResolver(allowLoopback))
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(10)
                .build();
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(config)
                // SEC-21: nunca segue redirect (302/307 para host interno não
                // passaria pela validação do DnsResolver no novo destino).
                .disableRedirectHandling()
                .build();
    }

    @Override
    public void deliver(OutboxNotification notification) {
        WebhookEventType type = parseType(notification.type());
        if (notification.merchantId() == null) {
            // Evento de PLATAFORMA (Spec 53, ex.: JOB_FAILED): destino = webhook
            // de operador, nao os webhooks de um lojista.
            deliverToOperator(notification, type);
            return;
        }
        List<MerchantWebhook> webhooks =
                webhookRepository.findAllActiveByMerchantAndEvent(notification.merchantId(), type);
        if (webhooks.isEmpty()) {
            // Fallback (Spec 7): sem webhook ativo para o evento, apenas loga.
            log.info("NOTIFICACAO [{}] merchant={} (sem webhook ativo - fallback log)",
                    type, notification.merchantId());
            return;
        }
        for (MerchantWebhook webhook : webhooks) {
            post(webhook.url(), webhook.secret(), webhook.isV2Signature(),
                    notification.payload(), notification.id(), type.headerValue());
        }
    }

    /**
     * Entrega de evento de plataforma (Spec 53) ao webhook de operador, com a
     * MESMA assinatura HMAC ({@code X-FoxCash-Signature}) do pipeline da Spec
     * 35. Sem webhook de operador configurado -> fallback log (fail-open).
     */
    private void deliverToOperator(OutboxNotification notification, WebhookEventType type) {
        String url = properties.getOperatorWebhookUrl();
        if (url == null || url.isBlank()) {
            log.info("NOTIFICACAO [{}] (evento de plataforma sem webhook de operador - fallback log)",
                    type);
            return;
        }
        // Discord NAO aceita o envelope puro (400 — exige campo "content").
        // Formatador nativo para webhook de operador do Discord (destino mais
        // comum de alerta); para outros destinos mantem o envelope assinado.
        String body = isDiscordUrl(url) ? discordBody(notification) : notification.payload();
        post(url, properties.getOperatorWebhookSecret(), true, body,
                notification.id(), type.headerValue());
    }

    /** True quando a URL é um webhook do Discord (formatador "content"). */
    static boolean isDiscordUrl(String url) {
        return url != null && (url.contains("discord.com/api/webhooks")
                || url.contains("discordapp.com/api/webhooks"));
    }

    /** Corpo compatível com o Discord: {@code {"content": "<texto>"}} (JSON escapado). */
    static String discordBody(OutboxNotification notification) {
        String text = "**FoxCash Ops — " + notification.type() + "**\n"
                + "eventId: " + notification.id() + "\n"
                + "data: " + notification.payload();
        return "{\"content\":\"" + escapeJson(text) + "\"}";
    }

    /** Escapa os caracteres que quebrariam o JSON do {@code content} do Discord. */
    static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Teste de entrega (Spec 35): envia um ping assinado para a URL informada —
     * nao persiste nada. Nunca lanca: retorna o resultado para o endpoint /test.
     */
    public DeliveryResult testDelivery(String url, String secret, String eventType) {
        String payload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"PING\","
                + "\"merchantId\":null,\"createdAt\":\"" + java.time.Instant.now()
                + "\",\"data\":{}}";
        try {
            return postRaw(url, secret, true, payload, null, "PING");
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    private void post(String url, String secret, boolean v2, String payload,
                      UUID eventId, String eventType) {
        try {
            DeliveryResult result = postRaw(url, secret, v2, payload, eventId, eventType);
            if (!result.delivered()) {
                // 4xx (Spec 148): erro do cliente — falha permanente, sem retry.
                if (result.permanent()) {
                    throw new PermanentWebhookDeliveryException(
                            "Webhook " + url + " rejeitado (4xx): " + result.error());
                }
                throw new IllegalStateException("Webhook " + url + " falhou: " + result.error());
            }
        } catch (PermanentWebhookDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Webhook " + url + " falhou: " + e.getMessage(), e);
        }
    }

    private DeliveryResult postRaw(String url, String secret, boolean v2, String payload,
                                   UUID eventId, String eventType) throws Exception {
        // SSRF (auditoria 2026-08-05): re-valida a URL no momento da entrega —
        // fast-fail de formato/host; a validação AUTORITATIVA dos endereços
        // usados na conexão é o WebhookDnsResolver do cliente (SEC-21 — fecha o
        // TOCTOU de DNS rebinding entre validação e connect).
        String safeUrl = WebhookUrlValidator.validate(url, properties.isAllowLoopback());
        HttpPost post = new HttpPost(URI.create(safeUrl));
        post.setHeader("Content-Type", "application/json");
        // Spec 148: formato v2 (t=,v1= — HMAC de "<t>.<rawBody>") por default;
        // v1 (hex puro) quando o registro do lojista ainda não migrou.
        post.setHeader(SIGNATURE_HEADER,
                v2 ? WebhookSignatureV2.serialize(secret, payload) : hmac(secret, payload));
        if (eventId != null) {
            post.setHeader(EVENT_ID_HEADER, eventId.toString());
        }
        if (eventType != null) {
            post.setHeader(EVENT_HEADER, eventType);
        }
        post.setEntity(new ByteArrayEntity(
                payload.getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON));
        try (ClassicHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            // Consome o corpo de forma LIMITADA e fecha a resposta (libera a
            // conexão do pool; receptor malicioso não pode inflar a memória).
            consumeBounded(response);
            if (status >= 200 && status < 300) {
                return DeliveryResult.ok(status);
            }
            // Spec 148: 4xx é erro do CLIENTE — retentar não resolve; sinaliza
            // como falha PERMANENTE para o processor encerrar as tentativas.
            if (status >= 400 && status < 500) {
                return DeliveryResult.permanent(status);
            }
            return DeliveryResult.failed("HTTP " + status);
        }
    }

    /** Lê (e descarta) até {@code RESPONSE_BODY_LIMIT} bytes do corpo de resposta. */
    private static void consumeBounded(ClassicHttpResponse response) throws Exception {
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return;
        }
        try (InputStream in = entity.getContent()) {
            in.readNBytes(RESPONSE_BODY_LIMIT);
        }
    }

    /** HMAC-SHA256 hex do raw body com o segredo do lojista (Spec 35). */
    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar webhook", e);
        }
    }

    private static WebhookEventType parseType(String type) {
        try {
            return WebhookEventType.valueOf(type);
        } catch (IllegalArgumentException e) {
            // Eventos legados (ex.: payload nao-envelope) seguem para o fallback log.
            throw new IllegalStateException("Tipo de evento desconhecido no outbox: " + type, e);
        }
    }

    /** Libera o pool de conexões no shutdown do contexto (bean singleton). */
    @PreDestroy
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
    }

    /** Resultado de uma entrega — usado pelo endpoint de teste (Spec 35). */
    public record DeliveryResult(boolean delivered, Integer statusCode, String error,
                                 boolean permanent) {

        public static DeliveryResult ok(int statusCode) {
            return new DeliveryResult(true, statusCode, null, false);
        }

        public static DeliveryResult failed(String error) {
            return new DeliveryResult(false, null, error, false);
        }

        /** 4xx — erro do CLIENTE (Spec 148): retentar não resolve; encerra. */
        public static DeliveryResult permanent(int statusCode) {
            return new DeliveryResult(false, statusCode, "HTTP " + statusCode, true);
        }
    }
}
