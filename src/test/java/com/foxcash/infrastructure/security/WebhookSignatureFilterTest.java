package com.foxcash.infrastructure.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.infrastructure.config.PspProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Testes do {@link WebhookSignatureFilter}: assinatura HMAC-SHA256 obrigatória em
 * /webhooks/pix (Spec 10/11) e /webhooks/payouts (Spec 36); Spec 59 — rate limit
 * por IP (429 ANTES do HMAC) e throttle do WARN de assinatura inválida.
 */
class WebhookSignatureFilterTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String HEADER = "X-Psp-Signature";
    private static final String BODY = "{\"externalReference\":\"E1234\",\"status\":\"PAID\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecurityAlertPublisher securityAlerts = mock(SecurityAlertPublisher.class);

    private PspProperties psp;

    @BeforeEach
    void setUp() {
        psp = new PspProperties();
        psp.getWebhook().setSecret(SECRET);
        psp.getWebhook().setVerifySignature(true);
    }

    private WebhookSignatureFilter filter(PspProperties psp, RateLimitProperties rl, Clock clock) {
        return new WebhookSignatureFilter(psp, rl, new InMemoryRateLimitStore(clock),
                objectMapper, false, clock, securityAlerts);
    }

    private MockHttpServletRequest postWebhook() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/pix");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("203.0.113.9");
        return request;
    }

    private int doFilter(WebhookSignatureFilter filter, MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    @Test
    void shouldNotFilterReturnsTrueForNonWebhookPaths() {
        // Mutation (BooleanFalseReturnVals L66): rotas fora de /webhooks/pix e
        // /webhooks/payouts NÃO são filtradas (o mutante sempre retornaria false).
        RateLimitProperties rl = new RateLimitProperties();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");

        assertThat(filter(psp, rl, Clock.systemUTC()).shouldNotFilter(request)).isTrue();
    }

    @Test
    void rejectsWebhookWithoutSignatureHeader() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), postWebhook(), chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsWebhookWithIncorrectSignature() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = postWebhook();
        request.addHeader(HEADER, "deadbeef");

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsWebhookWithValidSignature() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = postWebhook();
        request.addHeader(HEADER, WebhookSignatureFilter.computeSignature(SECRET, BODY.getBytes(StandardCharsets.UTF_8)));

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void acceptsPayloadExactlyAtSizeLimit() throws Exception {
        // Mutation (ConditionalsBoundary L78: rawBody.length > MAX → >=): payload
        // de EXATAMENTE 1 MiB é aceito (o mutante responderia 413).
        byte[] body = new byte[1_048_576];
        Arrays.fill(body, (byte) 'a');
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/pix");
        request.setContent(body);
        request.setRemoteAddr("203.0.113.9");
        request.addHeader(HEADER, WebhookSignatureFilter.computeSignature(SECRET, body));

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsPayloadAboveSizeLimit() throws Exception {
        // Auditoria 2026-08-05 (B4): payload acima de 1 MiB → 413 sem alocar tudo.
        byte[] body = new byte[1_048_577];
        Arrays.fill(body, (byte) 'a');
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/pix");
        request.setContent(body);
        request.setRemoteAddr("203.0.113.9");

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    // ---------- Spec 36: /webhooks/payouts usa o proprio segredo ----------

    private PspProperties payoutPsp() {
        PspProperties properties = new PspProperties();
        properties.getPayoutWebhook().setSecret("payout-secret");
        properties.getPayoutWebhook().setVerifySignature(true);
        return properties;
    }

    @Test
    void rejectsPayoutWebhookWithoutItsOwnSignature() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/payouts");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("203.0.113.9");
        // Assinatura do segredo DO PIX (errado p/ o payout) -> rejeitado.
        request.addHeader(HEADER, WebhookSignatureFilter.computeSignature(SECRET, BODY.getBytes(StandardCharsets.UTF_8)));

        int status = doFilter(filter(payoutPsp(), new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsPayoutWebhookWithItsOwnSignature() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/payouts");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("203.0.113.9");
        request.addHeader(HEADER, WebhookSignatureFilter.computeSignature("payout-secret", BODY.getBytes(StandardCharsets.UTF_8)));

        int status = doFilter(filter(payoutPsp(), new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    // ---------- Spec 59: rate limit por IP nos webhooks (429 ANTES do HMAC) ----------

    private RateLimitProperties webhookRl(int maxRequests) {
        RateLimitProperties rl = new RateLimitProperties();
        rl.setEnabled(true);
        rl.getWebhook().setEnabled(true);
        rl.getWebhook().setMaxRequests(maxRequests);
        rl.getWebhook().setWindowSeconds(60);
        return rl;
    }

    @Test
    void webhookFloodFromSameIpIsCutWith429EvenWithValidSignature() throws Exception {
        // Spec 59 critério 1: flood do MESMO IP acima do teto → 429. O rate limit
        // roda ANTES do HMAC — mesmo um webhook VÁLIDO acima do teto leva 429.
        WebhookSignatureFilter filter = filter(psp, webhookRl(2), Clock.systemUTC());

        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(200);
        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(200);
        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(429);
    }

    @Test
    void webhookFloodIsCutWith429BeforeHmacCheck() throws Exception {
        // Spec 59 decisão de design: 429 ANTES do HMAC. 1º request dentro do teto
        // com assinatura inválida → 401 (rate limit passou, HMAC rejeitou). 2º
        // request: bucket esgotado → 429 mesmo sem assinatura (que normalmente
        // seria 401) — prova que o flood é cortado antes de ler o body/HMAC.
        WebhookSignatureFilter filter = filter(psp, webhookRl(1), Clock.systemUTC());

        MockHttpServletRequest invalid = postWebhook();
        invalid.addHeader(HEADER, "deadbeef");
        assertThat(doFilter(filter, invalid, new MockFilterChain())).isEqualTo(401);

        MockHttpServletRequest noSignature = postWebhook();
        noSignature.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(doFilter(filter, noSignature, new MockFilterChain())).isEqualTo(429);
    }

    @Test
    void webhookRateLimitIsPerIpAndPerRoute() throws Exception {
        WebhookSignatureFilter filter = filter(psp, webhookRl(1), Clock.systemUTC());

        // /webhooks/pix do IP A → 1ª passa (200 com assinatura válida).
        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(200);
        // Mesma rota, mesmo IP → esgotou o bucket → 429 (antes do HMAC).
        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(429);
        // /webhooks/payouts (outro path → outro bucket) do MESMO IP → passa.
        MockHttpServletRequest payout = new MockHttpServletRequest("POST", "/webhooks/payouts");
        payout.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        payout.setRemoteAddr("203.0.113.9");
        payout.addHeader(HEADER, WebhookSignatureFilter.computeSignature("payout-secret", BODY.getBytes(StandardCharsets.UTF_8)));
        assertThat(doFilter(filter(payoutPsp(), webhookRl(1), Clock.systemUTC()), payout, new MockFilterChain()))
                .isEqualTo(200);
    }

    @Test
    void webhookRateLimitDisabledDoesNotBlock() throws Exception {
        RateLimitProperties rl = new RateLimitProperties();
        rl.setEnabled(true);
        rl.getWebhook().setEnabled(false); // grupo webhook desligado
        WebhookSignatureFilter filter = filter(psp, rl, Clock.systemUTC());

        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(200);
        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(200);
        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(200);
    }

    @Test
    void blockedWebhookRespondsWithJsonTooManyRequests() throws Exception {
        WebhookSignatureFilter filter = filter(psp, webhookRl(1), Clock.systemUTC());
        assertThat(doFilter(filter, signedWebhook(), new MockFilterChain())).isEqualTo(200); // 1º → 200

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(signedWebhook(), response, new MockFilterChain()); // 2º (request novo) → 429

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("too_many_requests");
    }

    private MockHttpServletRequest signedWebhook() {
        MockHttpServletRequest request = postWebhook();
        request.addHeader(HEADER, WebhookSignatureFilter.computeSignature(SECRET, BODY.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    // ---------- Spec 59: throttle do WARN de assinatura inválida ----------

    /** Clock mutável para avançar o tempo nos testes de throttle. */
    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private List<ILoggingEvent> captureWarns() {
        Logger logger = (Logger) LoggerFactory.getLogger(WebhookSignatureFilter.class);
        logger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender.list;
    }

    @Test
    void invalidSignatureWarnIsThrottledToOneLogPerWindow() throws Exception {
        // Spec 59 critério 3: N requests inválidos geram ≤ M linhas de log
        // (1 a cada log-throttle-seconds), não 1 por request.
        psp.getWebhook().setLogThrottleSeconds(10);
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T00:00:00Z"));
        WebhookSignatureFilter filter = filter(psp, webhookRl(1000), clock);
        List<ILoggingEvent> warns = captureWarns();

        for (int i = 0; i < 10; i++) {
            // Request NOVO a cada iteração (OncePerRequestFilter não re-executa no
            // mesmo request — atributo de dedupe).
            MockHttpServletRequest r = postWebhook();
            r.addHeader(HEADER, "deadbeef");
            doFilter(filter, r, new MockFilterChain());
        }
        long warnCount = warns.stream().filter(e -> e.getFormattedMessage().contains("assinatura")).count();
        assertThat(warnCount).isEqualTo(1); // 10 inválidos no mesmo intervalo → 1 log

        // Avança o intervalo → o próximo WARN é registrado (2 logs totais).
        clock.advance(Duration.ofSeconds(11));
        MockHttpServletRequest r = postWebhook();
        r.addHeader(HEADER, "deadbeef");
        doFilter(filter, r, new MockFilterChain());
        warnCount = warns.stream().filter(e -> e.getFormattedMessage().contains("assinatura")).count();
        assertThat(warnCount).isEqualTo(2);
    }

    @Test
    void invalidSignatureStillReturns401DespiteThrottle() throws Exception {
        // Spec 59 critério 3: o 401 NÃO muda com o throttle — só o log é cortado.
        psp.getWebhook().setLogThrottleSeconds(10);
        WebhookSignatureFilter filter = filter(psp, webhookRl(1000), Clock.systemUTC());

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = postWebhook();
            r.addHeader(HEADER, "deadbeef");
            assertThat(doFilter(filter, r, new MockFilterChain())).isEqualTo(401);
        }
    }

    // ---------- SEC-17: path traversal (`..`) não pode pular a verificação de assinatura ----------

    @Test
    void dotDotPathIsStillFilteredAsWebhook() {
        // SEC-17 (revisão de segurança 2026-08-11): o path é NORMALIZADO antes da
        // decisão. /webhooks/../webhooks/pix resolve para /webhooks/pix (o container
        // roteia para o controller do webhook) — o filtro DEVE rodar (shouldNotFilter
        // = false). Antes, o path cru não casava com o literal e a verificação HMAC
        // era pulada → PAID falso creditava o wallet sem posse do segredo do PSP.
        RateLimitProperties rl = new RateLimitProperties();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/../webhooks/pix");

        assertThat(filter(psp, rl, Clock.systemUTC()).shouldNotFilter(request)).isFalse();
    }

    @Test
    void dotDotPathWebhookRequiresValidSignature() throws Exception {
        // SEC-17: o webhook acessado via path traversal exige a MESMA assinatura
        // HMAC — sem assinatura → 401 (não processa).
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/../webhooks/pix");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("203.0.113.9");

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void dotDotPathWebhookAcceptsValidSignature() throws Exception {
        // SEC-17 (sem regressão): o webhook via `..` com assinatura VÁLIDA é aceito
        // (o payload assinado cobre o path normalizado — mesma rota real).
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/../webhooks/pix");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("203.0.113.9");
        request.addHeader(HEADER, WebhookSignatureFilter.computeSignature(SECRET, BODY.getBytes(StandardCharsets.UTF_8)));

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void dotDotPathSharesWebhookRateLimitBucket() throws Exception {
        // SEC-17: a chave do rate limit usa o path NORMALIZADO — /webhooks/../webhooks/pix
        // compartilha o bucket do /webhooks/pix real (sem bypass por cardinalidade
        // de variantes de path).
        RateLimitProperties rl = webhookRl(1); // teto 1
        WebhookSignatureFilter filter = filter(psp, rl, Clock.systemUTC());
        MockFilterChain chain = new MockFilterChain();

        // 1ª requisição (path real) → passa no rate limit (assinatura inválida → 401).
        MockHttpServletRequest real = postWebhook();
        assertThat(doFilter(filter, real, chain)).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        // 2ª requisição (mesma janela, path com `..`) → bucket atingido → 429.
        MockHttpServletRequest traversal = new MockHttpServletRequest("POST", "/webhooks/../webhooks/pix");
        traversal.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        traversal.setRemoteAddr("203.0.113.9");
        assertThat(doFilter(filter, traversal, chain)).isEqualTo(429);
    }

    // ---------- Spec 91: webhook por adquirente (Pagar.me HMAC / Asaas token) ----------

    @Test
    void pixWebhookAcceptedWithAsaasToken() throws Exception {
        // D6: o Asaas NÃO usa HMAC — envia o token da API no header (access_token),
        // comparado em tempo constante com foxcash.psp.asaas.secret-key.
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = postWebhook();
        psp.getAsaas().setSecretKey("asaas-api-token");
        request.addHeader("access_token", "asaas-api-token");

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void pixWebhookAcceptedWithXApiKeyToken() throws Exception {
        // Alternativa de header do Asaas (x-api-key) — mesma secret-key.
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = postWebhook();
        psp.getAsaas().setSecretKey("asaas-api-token");
        request.addHeader("x-api-key", "asaas-api-token");

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void pixWebhookRejectedWithWrongAsaasToken() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = postWebhook();
        psp.getAsaas().setSecretKey("real-token");
        request.addHeader("access_token", "wrong-token");

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void pixWebhookRejectedWithoutAnyAdquirenteSecret() throws Exception {
        // Fail-closed (D6): sem HMAC do Pagar.me E sem token do Asaas → 401.
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = postWebhook();
        request.addHeader(HEADER, "deadbeef");

        int status = doFilter(filter(psp, new RateLimitProperties(), Clock.systemUTC()), request, chain);

        assertThat(status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chain.getRequest()).isNull();
    }
}
