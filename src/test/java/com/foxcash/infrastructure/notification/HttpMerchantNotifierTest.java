package com.foxcash.infrastructure.notification;

import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.MerchantWebhookRepository;
import com.foxcash.infrastructure.config.NotificationProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testes do adapter HTTP de entrega de webhooks (Spec 35): POST assinado
 * HMAC-SHA256 com os headers de evento, fallback log sem webhook ativo, falha
 * em 4xx/5xx (dispara retry) e teste de entrega.
 */
class HttpMerchantNotifierTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String SECRET = "segredo-teste";

    private MockWebServer server;
    private MerchantWebhookRepository webhookRepository;
    private HttpMerchantNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        webhookRepository = mock(MerchantWebhookRepository.class);
        // allowLoopback=true: o MockWebServer escuta em 127.0.0.1 (receptor
        // local de teste) — em producao a flag permanece false (SSRF block).
        NotificationProperties properties = new NotificationProperties();
        properties.setAllowLoopback(true);
        notifier = new HttpMerchantNotifier(webhookRepository, properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private MerchantWebhook activeWebhook(WebhookEventType type) {
        return MerchantWebhook.create(MERCHANT_ID, server.url("/hook").toString(), SECRET, Set.of(type));
    }

    @Test
    void deliversSignedPostWhenWebhookActive() throws Exception {
        when(webhookRepository.findAllActiveByMerchantAndEvent(MERCHANT_ID, WebhookEventType.SALE_PAID))
                .thenReturn(List.of(activeWebhook(WebhookEventType.SALE_PAID)));
        server.enqueue(new MockResponse().setResponseCode(200));
        OutboxNotification notification = OutboxNotification.pending(MERCHANT_ID, "SALE_PAID", "{\"eventId\":\"e1\"}");

        notifier.deliver(notification);

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/hook");
        String body = request.getBody().readUtf8();
        assertThat(body).isEqualTo("{\"eventId\":\"e1\"}");
        // Spec 148: default v2 — X-FoxCash-Signature no formato t=<unix>,v1=<hex>
        // (HMAC de "<t>.<body>"); o receptor valida a frescura pelo t.
        String signature = request.getHeader("X-FoxCash-Signature");
        assertThat(signature).startsWith("t=").contains(",v1=");
        String[] parts = signature.split("v1=");
        assertThat(parts).hasSize(2);
        long t = Long.parseLong(signature.substring(2, signature.indexOf(",v1=")));
        assertThat(t).isGreaterThan(1_700_000_000L);
        assertThat(parts[1]).isEqualTo(hmac(SECRET, t + "." + body));
        assertThat(request.getHeader("X-FoxCash-Event-Id")).isEqualTo(notification.id().toString());
        // Spec 148: header de evento com valor de mercado (lowercase com pontos).
        assertThat(request.getHeader("X-FoxCash-Event")).isEqualTo("sale.paid");
    }

    @Test
    void deliversV1LegacySignatureWhenWebhookConfiguredForV1() throws Exception {
        MerchantWebhook v1 = activeWebhook(WebhookEventType.SALE_PAID)
                .withSignatureVersion(MerchantWebhook.SIGNATURE_VERSION_V1);
        when(webhookRepository.findAllActiveByMerchantAndEvent(MERCHANT_ID, WebhookEventType.SALE_PAID))
                .thenReturn(List.of(v1));
        server.enqueue(new MockResponse().setResponseCode(200));
        OutboxNotification notification = OutboxNotification.pending(MERCHANT_ID, "SALE_PAID", "{\"eventId\":\"e1\"}");

        notifier.deliver(notification);

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        String body = request.getBody().readUtf8();
        // v1 legado: hex puro do HMAC do raw body (Spec 35) — compatibilidade.
        assertThat(request.getHeader("X-FoxCash-Signature")).isEqualTo(hmac(SECRET, body));
    }

    @Test
    void throwsPermanentOn4xxSoDispatcherFailsWithoutRetry() {
        when(webhookRepository.findAllActiveByMerchantAndEvent(MERCHANT_ID, WebhookEventType.SALE_PAID))
                .thenReturn(List.of(activeWebhook(WebhookEventType.SALE_PAID)));
        server.enqueue(new MockResponse().setResponseCode(400));
        OutboxNotification notification = OutboxNotification.pending(MERCHANT_ID, "SALE_PAID", "{}");

        // Spec 148: 4xx = erro do cliente — falha PERMANENTE (não é retried).
        assertThatThrownBy(() -> notifier.deliver(notification))
                .isInstanceOf(PermanentWebhookDeliveryException.class);
    }

    @Test
    void doesNotPostWhenNoActiveWebhook() throws Exception {
        when(webhookRepository.findAllActiveByMerchantAndEvent(MERCHANT_ID, WebhookEventType.SALE_PAID))
                .thenReturn(List.of());
        OutboxNotification notification = OutboxNotification.pending(MERCHANT_ID, "SALE_PAID", "{}");

        // Fallback log — nao lanca e nao faz POST.
        notifier.deliver(notification);

        assertThat(server.takeRequest(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void throwsOn5xxSoDispatcherRetries() {
        when(webhookRepository.findAllActiveByMerchantAndEvent(MERCHANT_ID, WebhookEventType.SALE_PAID))
                .thenReturn(List.of(activeWebhook(WebhookEventType.SALE_PAID)));
        server.enqueue(new MockResponse().setResponseCode(500));
        OutboxNotification notification = OutboxNotification.pending(MERCHANT_ID, "SALE_PAID", "{}");

        assertThatThrownBy(() -> notifier.deliver(notification))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInternalUrlOnDeliveryWhenLoopbackNotAllowed() throws Exception {
        // SSRF (auditoria 2026-08-05): com allowLoopback=false, a entrega para
        // endereco interno e rejeitada ANTES do POST (o MockWebServer em
        // 127.0.0.1 nunca recebe a requisicao).
        NotificationProperties strict = new NotificationProperties(); // allowLoopback = false
        HttpMerchantNotifier strictNotifier = new HttpMerchantNotifier(webhookRepository, strict);
        MerchantWebhook internal = MerchantWebhook.create(MERCHANT_ID,
                server.url("/hook").toString(), SECRET, Set.of(WebhookEventType.SALE_PAID));
        when(webhookRepository.findAllActiveByMerchantAndEvent(MERCHANT_ID, WebhookEventType.SALE_PAID))
                .thenReturn(List.of(internal));
        OutboxNotification notification = OutboxNotification.pending(MERCHANT_ID, "SALE_PAID", "{}");

        assertThatThrownBy(() -> strictNotifier.deliver(notification))
                .isInstanceOf(IllegalStateException.class);

        assertThat(server.takeRequest(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void testDeliveryReportsSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        HttpMerchantNotifier.DeliveryResult result =
                notifier.testDelivery(server.url("/ping").toString(), SECRET, "SALE_PAID");

        assertThat(result.delivered()).isTrue();
        assertThat(result.statusCode()).isEqualTo(204);
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request.getHeader("X-FoxCash-Event")).isEqualTo("PING");
    }

    @Test
    void testDeliveryReportsFailureWithoutThrowing() {
        server.enqueue(new MockResponse().setResponseCode(404));

        HttpMerchantNotifier.DeliveryResult result =
                notifier.testDelivery(server.url("/ping").toString(), SECRET, "SALE_PAID");

        assertThat(result.delivered()).isFalse();
        assertThat(result.error()).contains("404");
    }

    // ---------- Spec 53: evento de PLATAFORMA (sem merchant) ----------

    @Test
    void platformEventDeliversToOperatorWebhookWithOperatorSecret() throws Exception {
        // Evento de plataforma (ex.: JOB_FAILED) vai ao webhook de OPERADOR,
        // com a assinatura v2 (Spec 148 — default) do segredo do operador —
        // nunca consulta os webhooks do lojista.
        NotificationProperties withOperator = new NotificationProperties();
        withOperator.setAllowLoopback(true);
        withOperator.setOperatorWebhookUrl(server.url("/ops").toString());
        withOperator.setOperatorWebhookSecret("ops-secret");
        HttpMerchantNotifier operatorNotifier = new HttpMerchantNotifier(webhookRepository, withOperator);
        server.enqueue(new MockResponse().setResponseCode(200));
        OutboxNotification notification = OutboxNotification.pending(null, "JOB_FAILED", "{\"eventId\":\"e1\"}");

        operatorNotifier.deliver(notification);

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/ops");
        String body = request.getBody().readUtf8();
        String signature = request.getHeader("X-FoxCash-Signature");
        assertThat(signature).startsWith("t=").contains(",v1=");
        long t = Long.parseLong(signature.substring(2, signature.indexOf(",v1=")));
        assertThat(signature.substring(signature.indexOf("v1=") + 3))
                .isEqualTo(hmac("ops-secret", t + "." + body));
        assertThat(request.getHeader("X-FoxCash-Event")).isEqualTo("job.failed");
        assertThat(request.getHeader("X-FoxCash-Event-Id")).isEqualTo(notification.id().toString());
        verifyNoInteractions(webhookRepository);
    }

    @Test
    void platformEventWithoutOperatorWebhookFallsBackToLog() throws Exception {
        // Sem webhook de operador configurado -> fail-open: apenas loga, nao
        // lanca e nao faz POST (alerta base = metrica + log + job_runs).
        OutboxNotification notification = OutboxNotification.pending(null, "JOB_FAILED", "{}");

        assertThatCode(() -> notifier.deliver(notification)).doesNotThrowAnyException();

        verifyNoInteractions(webhookRepository);
        assertThat(server.takeRequest(500, TimeUnit.MILLISECONDS)).isNull();
    }

    // ---------- Operador: formatador nativo do Discord (R3 2026-08-10) ----------

    @Test
    void discordUrlDetection() {
        assertThat(HttpMerchantNotifier.isDiscordUrl(
                "https://discord.com/api/webhooks/1/abc")).isTrue();
        assertThat(HttpMerchantNotifier.isDiscordUrl(
                "https://discordapp.com/api/webhooks/1/abc")).isTrue();
        assertThat(HttpMerchantNotifier.isDiscordUrl(
                "https://hooks.example.com/ops")).isFalse();
        assertThat(HttpMerchantNotifier.isDiscordUrl(null)).isFalse();
    }

    @Test
    void discordBodyBuildsContentJsonEscapingQuotesAndNewlines() {
        OutboxNotification notification = OutboxNotification.pending(
                null, "BODY_INSPECTION_BLOCKED",
                "{\"path\":\"/api/v1/merchants\",\"pattern\":\"script-tag\",\"category\":\"xss\"}");
        String body = HttpMerchantNotifier.discordBody(notification);

        // JSON válido com campo "content" (o Discord exige) e aspas escapadas.
        assertThat(body).startsWith("{\"content\":\"");
        assertThat(body).contains("BODY_INSPECTION_BLOCKED");
        assertThat(body).doesNotContain("\"path\":\""); // aspas internas escapadas
        assertThat(body).contains("\\\"");              // escape de aspas presente
        assertThat(body).doesNotContain("\n");          // quebras de linha escapadas
    }

    @Test
    void escapeJsonHandlesBackslashQuoteAndNewline() {
        assertThat(HttpMerchantNotifier.escapeJson("a\"b\\c\nd"))
                .isEqualTo("a\\\"b\\\\c\\nd");
    }

    private static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
