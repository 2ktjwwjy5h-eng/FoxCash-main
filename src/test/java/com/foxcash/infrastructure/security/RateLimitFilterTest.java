package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.domain.port.out.RateLimitStore;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link RateLimitFilter} (Spec 18, ADR-009): janela fixa por
 * IP+rota nas rotas públicas de abuso; demais rotas sem limite. O contador é
 * delegado a um {@link RateLimitStore} (Spec 43 — M-05): aqui o store é o
 * {@link InMemoryRateLimitStore} real (mesmo comportamento) e há um teste com
 * store mockado (fail-closed/bloqueio direto). Auditoria 2026-08-05 (M10):
 * X-Forwarded-For só é considerado com trust-proxy=true.
 */
class RateLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecurityAlertPublisher securityAlerts = mock(SecurityAlertPublisher.class);
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private RateLimitFilter filter(int maxRequests, boolean enabled, boolean trustProxy) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(enabled);
        properties.setMaxRequests(maxRequests);
        properties.setWindowSeconds(60);
        return new RateLimitFilter(properties, objectMapper, trustProxy,
                new InMemoryRateLimitStore(Clock.systemUTC()), securityAlerts, meterRegistry);
    }

    private int post(String path, RateLimitFilter filter) throws Exception {
        return post(path, null, filter);
    }

    private int post(String path, String forwardedFor, RateLimitFilter filter) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("203.0.113.9");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private int get(String path, RateLimitFilter filter) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("203.0.113.9");
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void allowsRequestsUpToTheLimitThenReturns429() throws Exception {
        RateLimitFilter filter = filter(2, true, false);

        assertThat(post("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/merchants", filter)).isEqualTo(429);
    }

    @Test
    void limitedPathsArePerRoute() throws Exception {
        RateLimitFilter filter = filter(1, true, false);

        assertThat(post("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        // Rota diferente (login master) tem janela própria — não é afetada pelo limite do cadastro.
        assertThat(post("/api/v1/auth/master/login", filter)).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void nonLimitedPathsAreNeverBlocked() throws Exception {
        RateLimitFilter filter = filter(1, true, false);

        // Rota autenticada (ex.: transações) não passa pelo rate limiter.
        assertThat(post("/api/v1/transactions", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/transactions", filter)).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void resetPasswordIsRateLimited() throws Exception {
        // B-02 (auditoria 2026-08-05): todo endpoint público de auth é limitado
        // por IP+rota — reset-password (público; a posse do token é a credencial)
        // entra na mesma janela dos demais.
        RateLimitFilter filter = filter(2, true, false);

        assertThat(post("/api/v1/merchant-auth/reset-password", filter))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/merchant-auth/reset-password", filter))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/merchant-auth/reset-password", filter)).isEqualTo(429);
    }

    @Test
    void storefrontGetIsRateLimited() throws Exception {
        // Spec 48: o storefront (consumido por bots) é GET mas entra no rate
        // limiter por IP — sem limite, uma chave vazada permite abuso sem custo.
        RateLimitFilter filter = filter(2, true, false);

        assertThat(get("/api/v1/foxcash/storefront", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(get("/api/v1/foxcash/storefront", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(get("/api/v1/foxcash/storefront", filter)).isEqualTo(429);
        // Outro GET (autenticado) continua sem limite.
        assertThat(get("/api/v1/transactions", filter)).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void disabledFilterNeverBlocks() throws Exception {
        RateLimitFilter filter = filter(1, false, false);

        assertThat(post("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void spoofedForwardedForDoesNotBypassRateLimitByDefault() throws Exception {
        // Auditoria 2026-08-05 (M10): sem trust-proxy, o X-Forwarded-For
        // falsificado é IGNORADO — o limite é por remoteAddr.
        RateLimitFilter filter = filter(1, true, false);

        assertThat(post("/api/v1/merchants", "1.2.3.4", filter)).isEqualTo(HttpServletResponse.SC_OK);
        // Mesmo remoteAddr com header diferente => mesmo bucket => 429.
        assertThat(post("/api/v1/merchants", "5.6.7.8", filter)).isEqualTo(429);
    }

    @Test
    void trustsForwardedForWhenProxyConfigured() throws Exception {
        // Atras de proxy reverso confiavel (trust-proxy=true): o primeiro valor
        // do X-Forwarded-For define o bucket (IPs distintos nao compartilham).
        RateLimitFilter filter = filter(1, true, true);

        assertThat(post("/api/v1/merchants", "1.2.3.4", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/merchants", "5.6.7.8", filter)).isEqualTo(HttpServletResponse.SC_OK);
        // Mesmo IP via XFF => limite atingido.
        assertThat(post("/api/v1/merchants", "1.2.3.4", filter)).isEqualTo(429);
    }

    // ---------- Spec 43 (M-05): o filtro delega o contador ao RateLimitStore ----------

    @Test
    void delegatesToStoreAndBlocksWhenStoreDenies() throws Exception {
        // Store mockado retornando false (limite excedido) → o filtro responde 429
        // sem depender do store concreto (memory/redis — mesmo contrato).
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.setWindowSeconds(60);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false, store, securityAlerts, meterRegistry);

        assertThat(post("/api/v1/merchants", filter)).isEqualTo(429);
        // A chave é rota|ip e os parâmetros vêm da config.
        verify(store).tryAcquire("/api/v1/merchants|203.0.113.9", 20, 60L);
    }

    @Test
    void passesWhenStoreAllowsAndDoesNotHitDenyPath() throws Exception {
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.setWindowSeconds(60);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false, store, securityAlerts, meterRegistry);

        assertThat(post("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        verify(store).tryAcquire("/api/v1/merchants|203.0.113.9", 20, 60L);
        // Store permitiu → o chain prossegue (200), sem bloquear.
    }

    // ---------- round 8 (mutations: L79/L100/L101/L107) ----------

    @Test
    void nonPostOnLimitedPathIsNeverRateLimited() throws Exception {
        // Mutation (BooleanFalseReturnVals L79): o `return true` de métodos
        // não-POST trocado por false — GET em rota limitada passaria a ser
        // bloqueado. Só POST (e Storefront GET) são limitados; GET em rota
        // limitada NUNCA é bloqueado, mesmo acima do limite.
        RateLimitFilter filter = filter(1, true, false);

        assertThat(get("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(get("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(get("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void planPurchasePostIsRateLimited() throws Exception {
        // Spec 49: compra de plano (POST /api/v1/foxcash/plans/{id}) é limitada por
        // IP — cobre a branch de prefixo (mutation: BooleanTrueReturnVals L83).
        RateLimitFilter filter = filter(2, true, false);

        assertThat(post("/api/v1/foxcash/plans/plano-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/foxcash/plans/plano-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/foxcash/plans/plano-1", filter)).isEqualTo(429);
    }

    @Test
    void blockedResponseIsJsonWithTooManyRequestsBody() throws Exception {
        // Mutations (VoidMethodCall L100/L101): setContentType e write removidos
        // — o 429 deve ser JSON com o corpo "too_many_requests".
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.setWindowSeconds(60);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false, store, securityAlerts, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchants");
        request.setRemoteAddr("203.0.113.9");
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("too_many_requests");
        // Spec 126: métrica de 429 com o grupo (rota pública → o próprio path).
        assertThat(meterRegistry.get("foxcash_http_429_total")
                .tag("group", "/api/v1/merchants").counter().count()).isEqualTo(1);
    }

    @Test
    void passesRequestToChainWhenStoreAllows() throws Exception {
        // Mutation (VoidMethodCall L107): chain.doFilter removido — quando o
        // store permite, a requisição DEVE prosseguir no chain.
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.setWindowSeconds(60);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false, store, securityAlerts, meterRegistry);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchants");
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    // ---------- Spec 58: rotas transacionais ampliadas por IP ----------

    @Test
    void transactionalPathsAreRateLimitedByIpWithHigherTier() throws Exception {
        // Spec 58: rotas transacionais autenticadas ganham limite por IP (teto
        // próprio, baseline 300) — rede de segurança além do limite por usuário.
        // A chave do bucket é path|ip: as 3 chamadas usam o MESMO path para
        // cair na mesma janela (paths diferentes seriam janelas separadas).
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);          // teto padrão (públicas)
        properties.setWindowSeconds(60);
        properties.getTransactional().setEnabled(true);
        properties.getTransactional().setMaxRequests(2); // teto transacional reduzido p/ teste
        properties.getTransactional().setWindowSeconds(60);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false,
                new InMemoryRateLimitStore(Clock.systemUTC()), securityAlerts, meterRegistry);

        assertThat(post("/api/v1/transactions/tx-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/transactions/tx-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/transactions/tx-1", filter)).isEqualTo(429);
    }

    @Test
    void botPollingGetOnDeliveriesIsRateLimitedByIp() throws Exception {
        // Spec 58: o bot polla GET /foxcash/deliveries/pending — limitado por IP
        // (teto folgado no baseline 300/min; aqui reduzido para o teste).
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.getTransactional().setEnabled(true);
        properties.getTransactional().setMaxRequests(2);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false,
                new InMemoryRateLimitStore(Clock.systemUTC()), securityAlerts, meterRegistry);

        assertThat(get("/api/v1/foxcash/deliveries/pending", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(get("/api/v1/foxcash/deliveries/pending", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(get("/api/v1/foxcash/deliveries/pending", filter)).isEqualTo(429);
    }

    @Test
    void transactionalTierUsesItsOwnWindow() throws Exception {
        // Rota transacional usa a janela/teto transacional; rota pública padrão
        // usa a janela padrão — sem vazamento entre os grupos.
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(1);            // padrão: 1
        properties.getTransactional().setEnabled(true);
        properties.getTransactional().setMaxRequests(1); // transacional: 1
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false,
                new InMemoryRateLimitStore(Clock.systemUTC()), securityAlerts, meterRegistry);

        assertThat(post("/api/v1/transactions/tx-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK); // janela própria
        // Mesmo path transacional (mesma janela path|ip) → estourou o teto do grupo.
        assertThat(post("/api/v1/transactions/tx-1", filter)).isEqualTo(429);
    }

    @Test
    void transactionalFilterDisabledDoesNotLimit() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.getTransactional().setEnabled(false); // grupo transacional desligado
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false,
                new InMemoryRateLimitStore(Clock.systemUTC()), securityAlerts, meterRegistry);

        assertThat(get("/api/v1/foxcash/deliveries/pending", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(get("/api/v1/foxcash/deliveries/pending", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(get("/api/v1/foxcash/deliveries/pending", filter)).isEqualTo(HttpServletResponse.SC_OK);
    }

    // ---------- SEC-02: chave normalizada — {id} distinto não cria bucket novo ----------

    @Test
    void differentPathIdsShareTheSameBucket() throws Exception {
        // SEC-02 (revisão de segurança 2026-08-11): a chave do bucket usa a rota
        // NORMALIZADA (prefixo fixo). Antes, /transactions/tx-1 e /transactions/tx-2
        // criavam buckets separados — um bot alternando ids contornava o teto.
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.getTransactional().setEnabled(true);
        properties.getTransactional().setMaxRequests(2);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false,
                new InMemoryRateLimitStore(Clock.systemUTC()), securityAlerts, meterRegistry);

        assertThat(post("/api/v1/transactions/tx-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        // Id DIFERENTE cai no MESMO bucket (prefixo /api/v1/transactions) → teto atingido.
        assertThat(post("/api/v1/transactions/tx-2", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/transactions/tx-3", filter)).isEqualTo(429);
    }

    @Test
    void planPurchaseIdsShareTheSameBucket() throws Exception {
        // SEC-02: compra de plano ({id} no path) compartilha o bucket por prefixo.
        RateLimitFilter filter = filter(2, true, false);

        assertThat(post("/api/v1/foxcash/plans/plano-1/purchase", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/foxcash/plans/plano-2/purchase", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/foxcash/plans/plano-3/purchase", filter)).isEqualTo(429);
    }

    // ---------- SEC-16: path traversal (`..`) não troca o bucket do rate limit ----------

    @Test
    void dotDotPathCannotBypassIntoTransactionalBucket() throws Exception {
        // SEC-16 (revisão de segurança 2026-08-11): uma URI com `..` é NORMALIZADA
        // (StringUtils.cleanPath) ANTES da decisão de teto. /api/v1/transactions/../auth/
        // master/login resolve para /api/v1/auth/master/login — rota pública de login
        // (teto PADRÃO baixo), NÃO o grupo transacional (teto alto). Antes, o path cru
        // começava com /api/v1/transactions/ → caía no bucket transacional → o atacante
        // contornava o rate limit do login com path traversal.
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(2);          // teto padrão (login): 2
        properties.setWindowSeconds(60);
        properties.getTransactional().setEnabled(true);
        properties.getTransactional().setMaxRequests(100); // transacional: folgado
        properties.getTransactional().setWindowSeconds(60);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false,
                new InMemoryRateLimitStore(Clock.systemUTC()), securityAlerts, meterRegistry);

        // 3 POSTs com `..` resolvendo para o login → estoura o teto PADRÃO (2).
        assertThat(post("/api/v1/transactions/../auth/master/login", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/transactions/../auth/master/login", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/transactions/../auth/master/login", filter)).isEqualTo(429);
    }

    @Test
    void dotDotPathUsesSameBucketAsRealLogin() throws Exception {
        // SEC-16: o path com `..` compartilha o bucket do login REAL (mesma rota
        // normalizada) — alternar entre a forma crua e a forma com `..` não cria
        // buckets separados (sem bypass por cardinalidade de variantes de path).
        RateLimitFilter filter = filter(2, true, false);

        assertThat(post("/api/v1/auth/master/login", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/transactions/../auth/master/login", filter)).isEqualTo(HttpServletResponse.SC_OK);
        // Mesma janela → teto atingido.
        assertThat(post("/api/v1/auth/master/login", filter)).isEqualTo(429);
    }

    // ---------- Spec 69 (critério 12): step-up MFA é limitado por IP ----------

    @Test
    void masterStepUpIsRateLimitedPerIp() throws Exception {
        // Spec 69 (critério 12): POST /api/v1/master/mfa/step-up é superfície de
        // verificação de código — entra no LIMITED_PATHS (bucket por IP+rota,
        // D6.2). 3 chamadas com teto 2 → 429; outra rota master autenticada
        // (fora do LIMITED_PATHS) não é afetada pelo teto PADRÃO.
        RateLimitFilter filter = filter(2, true, false);

        assertThat(post("/api/v1/master/mfa/step-up", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/master/mfa/step-up", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(post("/api/v1/master/mfa/step-up", filter)).isEqualTo(429);

        // Rota autenticada do master (ex.: bloqueio) segue sem limite por IP.
        assertThat(post("/api/v1/master/merchants/111/withdrawals/block", filter))
                .isEqualTo(HttpServletResponse.SC_OK);
    }

    // ---------- retomada (Spec 58 + Spec 64): janela transacional + alerta de rajada ----------

    @Test
    void transactionalBlockedAlertsGroupAndUsesTransactionalWindow() throws Exception {
        // L151 (NegateConditionalsMutator no ternário): rota transacional usa a
        // JANELA/TETO transacionais no tryAcquire. L155 (VoidMethodCallMutator) +
        // L178 (NegateConditionalsMutator) + L179 (EmptyObjectReturnValsMutator):
        // o 429 de rota transacional alerta com o grupo "transactional".
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.setWindowSeconds(60);
        properties.getTransactional().setEnabled(true);
        properties.getTransactional().setMaxRequests(5);
        properties.getTransactional().setWindowSeconds(30);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false, store, securityAlerts, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/transactions/tx-1");
        request.setRemoteAddr("203.0.113.9");
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        verify(store).tryAcquire(anyString(), eq(5), eq(30L));
        verify(securityAlerts).recordRateLimitBurst("transactional");
    }

    @Test
    void blockedPublicRouteAlertsWithPathGroup() throws Exception {
        // L181 (EmptyObjectReturnValsMutator): para rota pública (não
        // transacional) o grupo do alerta é o PRÓPRIO path — o mutante devolveria
        // "" e quebraria a identificação da rota abusada.
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.setWindowSeconds(60);
        RateLimitFilter filter = new RateLimitFilter(properties, objectMapper, false, store, securityAlerts, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchants");
        request.setRemoteAddr("203.0.113.9");
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        verify(securityAlerts).recordRateLimitBurst("/api/v1/merchants");
    }
}
