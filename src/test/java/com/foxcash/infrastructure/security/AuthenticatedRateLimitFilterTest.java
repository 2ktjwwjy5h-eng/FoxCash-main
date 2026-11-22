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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link AuthenticatedRateLimitFilter} (Spec 58): rate limit por
 * PRINCIPAL autenticado (API key/token) nas rotas transacionais.
 *
 * <p>Pontos críticos cobertos:
 * <ul>
 *   <li>principal autenticado é corretamente identificado (chave por
 *       merchantId/masterId/"admin", não por IP);</li>
 *   <li>lojas diferentes NÃO compartilham o bucket (isolamento por principal);</li>
 *   <li>requests anônimos (sem principal) seguem SEM limite (fluxo existente);</li>
 *   <li>rotas fora de /api/v1 (actuator/health) nunca são limitadas;</li>
 *   <li>não há dupla execução (OncePerRequestFilter — chamadas repetidas do
 *       mesmo request não re-contam).</li>
 * </ul>
 */
class AuthenticatedRateLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecurityAlertPublisher securityAlerts = mock(SecurityAlertPublisher.class);
    // Spec 98 (D4.3): override rps/rpm por lojista — mock sem override = fail-closed (global).
    private final com.foxcash.domain.port.out.MerchantConfigProvider merchantConfigProvider =
            mock(com.foxcash.domain.port.out.MerchantConfigProvider.class);
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private AuthenticatedRateLimitFilter filter(int maxRequests, boolean enabled, boolean authenticatedEnabled) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(enabled);
        properties.getAuthenticated().setEnabled(authenticatedEnabled);
        properties.getAuthenticated().setMaxRequests(maxRequests);
        properties.getAuthenticated().setWindowSeconds(60);
        return new AuthenticatedRateLimitFilter(properties, objectMapper,
                new InMemoryRateLimitStore(Clock.systemUTC()), securityAlerts, merchantConfigProvider, meterRegistry);
    }

    private void authenticate(UUID principal) {
        authenticate(principal, "ROLE_MERCHANT");
    }

    private void authenticate(UUID principal, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private int call(String method, String path, AuthenticatedRateLimitFilter filter) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void allowsUpToLimitThenReturns429ForKeyedPrincipal() throws Exception {
        authenticate(UUID.randomUUID());
        AuthenticatedRateLimitFilter filter = filter(2, true, true);

        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(429);
        clearAuth();
    }

    @Test
    void differentPrincipalsDoNotShareTheBucket() throws Exception {
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();
        AuthenticatedRateLimitFilter filter = filter(1, true, true);

        authenticate(merchantA);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(429);
        clearAuth();

        // Loja B (outro principal) tem janela própria — não é afetada pelo limite da A.
        authenticate(merchantB);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        clearAuth();
    }

    @Test
    void differentRoutesDoNotShareTheBucket() throws Exception {
        authenticate(UUID.randomUUID());
        AuthenticatedRateLimitFilter filter = filter(1, true, true);

        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        // Rota diferente (transactions) tem janela própria por principal.
        assertThat(call("GET", "/api/v1/transactions", filter)).isEqualTo(HttpServletResponse.SC_OK);
        clearAuth();
    }

    @Test
    void anonymousRequestsAreNeverRateLimited() throws Exception {
        clearAuth();
        AuthenticatedRateLimitFilter filter = filter(1, true, true);

        // Sem principal → segue o fluxo existente (sem limite neste filtro; o
        // RateLimitFilter por IP cobre as rotas públicas).
        assertThat(call("POST", "/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/merchants", filter)).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void actuatorAndNonApiPathsAreNeverRateLimited() throws Exception {
        authenticate(UUID.randomUUID());
        AuthenticatedRateLimitFilter filter = filter(1, true, true);

        assertThat(call("GET", "/actuator/health", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/actuator/health", filter)).isEqualTo(HttpServletResponse.SC_OK);
        clearAuth();
    }

    @Test
    void disabledFilterNeverBlocks() throws Exception {
        authenticate(UUID.randomUUID());
        AuthenticatedRateLimitFilter filter = filter(1, true, false);

        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        clearAuth();
    }

    @Test
    void blockedResponseIsJsonWithTooManyRequestsBody() throws Exception {
        authenticate(UUID.randomUUID());
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(properties, objectMapper, store, securityAlerts, merchantConfigProvider, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/foxcash/pix/cob");
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("too_many_requests");
        // Spec 126: métrica de 429 (grupo "authenticated" — por principal).
        assertThat(meterRegistry.get("foxcash_http_429_total")
                .tag("group", "authenticated").counter().count()).isEqualTo(1);
        clearAuth();
    }

    @Test
    void usesPrincipalInTheStoreKeyNotIp() throws Exception {
        UUID merchantId = UUID.randomUUID();
        authenticate(merchantId);
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(properties, objectMapper, store, securityAlerts, merchantConfigProvider, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/foxcash/pix/cob");
        filter.doFilter(request, response, new MockFilterChain());

        // Chave = path|principal (merchantId), NÃO path|IP.
        verify(store).tryAcquire("/api/v1/foxcash/pix/cob|" + merchantId, 120, 60L);
        clearAuth();
    }

    @Test
    void oncePerRequestDoesNotDoubleCountSameRequest() throws Exception {
        // OncePerRequestFilter: o mesmo request filtrado 2x (ex.: container +
        // cadeia de segurança) conta UMA vez — sem dupla execução do limite.
        authenticate(UUID.randomUUID());
        AuthenticatedRateLimitFilter filter = filter(2, true, true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/foxcash/pix/cob");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());
        // Spring 6.1+ (Boot 3.3) remove o atributo "já filtrado" ao final do
        // doFilter; num request real o container o mantém durante TODO o
        // dispatch — é assim que um 2º passe é ignorado. Simula esse ciclo
        // (sem ele, 3 doFilter diretos re-contariam e o 3º viraria 429).
        request.setAttribute(filter.getClass().getName() + ".FILTERED", Boolean.TRUE);
        filter.doFilter(request, response, new MockFilterChain());
        filter.doFilter(request, response, new MockFilterChain());

        // 3 filtragens do MESMO request → só a 1ª conta (2 permitidas restam).
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        clearAuth();
    }

    // ---------- SEC-02: chave normalizada — {id} distinto não cria bucket novo ----------

    @Test
    void differentPathIdsShareTheSameBucketPerPrincipal() throws Exception {
        // SEC-02 (revisão de segurança 2026-08-11): a chave usa a rota NORMALIZADA
        // (prefixo fixo). Antes, /transactions/tx-1 e /transactions/tx-2 criavam
        // buckets separados por principal — um bot alternando ids contornava o teto.
        UUID merchantId = UUID.randomUUID();
        authenticate(merchantId);
        AuthenticatedRateLimitFilter filter = filter(2, true, true);

        assertThat(call("GET", "/api/v1/transactions/tx-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        // Id DIFERENTE cai no MESMO bucket (prefixo /api/v1/transactions) → teto atingido.
        assertThat(call("GET", "/api/v1/transactions/tx-2", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/api/v1/transactions/tx-3", filter)).isEqualTo(429);
        clearAuth();
    }

    @Test
    void normalizedKeyUsesPrefixNotFullUri() throws Exception {
        // Verifica a chave exata passada ao store: prefixo fixo + principal
        // (não a URI completa com o id).
        UUID merchantId = UUID.randomUUID();
        authenticate(merchantId);
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(properties, objectMapper, store, securityAlerts, merchantConfigProvider, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/tx-42");
        filter.doFilter(request, response, new MockFilterChain());

        verify(store).tryAcquire("/api/v1/transactions|" + merchantId, 120, 60L);
        clearAuth();
    }

    // ---------- SEC-23: rotas de RECURSOS do lojista também colapsam por prefixo ----------

    @Test
    void merchantResourceIdsShareTheSameBucketPerPrincipal() throws Exception {
        // SEC-23 (revisão de segurança 2026-08-11): /api/v1/merchants/{id}/... e
        // /api/v1/portal/{resource}/{id} tinham {id} SEM colapso — um lojista
        // criava N recursos (plans/webhooks/stores/sales/... do próprio id) e
        // ganhava N buckets do limite por PRINCIPAL (Spec 58) → vazão N × teto
        // (bypass de cardinalidade, mesma classe do SEC-02). Agora os prefixos
        // colapsam: ids distintos caem no MESMO bucket do principal.
        UUID merchantId = UUID.randomUUID();
        authenticate(merchantId);
        AuthenticatedRateLimitFilter filter = filter(2, true, true);

        // 3 ids distintos da MESMA rota de recurso → mesmo bucket → 3ª vira 429.
        assertThat(call("GET", "/api/v1/merchants/" + merchantId + "/withdrawals/111", filter))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/api/v1/merchants/" + merchantId + "/withdrawals/222", filter))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/api/v1/merchants/" + merchantId + "/withdrawals/333", filter))
                .isEqualTo(429);

        // Outra rota de recurso (portal/plans/{id}) também colapsa (bucket próprio
        // da rota, mas sem cardinalidade por id).
        assertThat(call("GET", "/api/v1/portal/plans/aaa", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/api/v1/portal/plans/bbb", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/api/v1/portal/plans/ccc", filter)).isEqualTo(429);
        clearAuth();
    }

    @Test
    void merchantResourceKeyUsesPrefixNotFullUri() throws Exception {
        // Verifica a chave exata do store para uma rota de recurso com {id}:
        // prefixo fixo (não a URI com o id do recurso).
        UUID merchantId = UUID.randomUUID();
        authenticate(merchantId);
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(properties, objectMapper, store, securityAlerts, merchantConfigProvider, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/merchants/" + merchantId + "/withdrawals/" + UUID.randomUUID());
        filter.doFilter(request, response, new MockFilterChain());

        verify(store).tryAcquire("/api/v1/merchants|" + merchantId, 120, 60L);
        clearAuth();
    }

    // ---------- SEC-16: path traversal (`..`) não cria bucket distinto ----------

    @Test
    void dotDotPathUsesSameBucketPerPrincipal() throws Exception {
        // SEC-16 (revisão de segurança 2026-08-11): a chave usa o path NORMALIZADO
        // (StringUtils.cleanPath colapsa `..`). /api/v1/transactions/../billing/
        // preview resolve para /api/v1/billing/preview — o mesmo principal NÃO pode
        // criar bucket distinto alternando a forma crua com variantes de `..`.
        UUID merchantId = UUID.randomUUID();
        authenticate(merchantId);
        AuthenticatedRateLimitFilter filter = filter(2, true, true);

        assertThat(call("GET", "/api/v1/transactions/tx-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/api/v1/transactions/tx-1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        // Forma crua com `..` que resolve para o MESMO path → mesmo bucket → teto.
        assertThat(call("GET", "/api/v1/transactions/./tx-1", filter)).isEqualTo(429);
        clearAuth();
    }

    @Test
    void dotDotPathCannotBypassOutOfApiPrefix() throws Exception {
        // SEC-16: /api/v1/../actuator resolve para /actuator (fora da API) — o
        // filtro não deve aplicar rate limit autenticado a essa rota (o path
        // normalizado não começa com /api/v1/). Antes, o prefixo cru /api/v1/../…
        // passava no shouldNotFilter e o principal autenticado era contado.
        UUID merchantId = UUID.randomUUID();
        authenticate(merchantId);
        AuthenticatedRateLimitFilter filter = filter(1, true, true);

        // Fora da API (após normalização) → nunca bloqueia.
        assertThat(call("GET", "/api/v1/../actuator/health", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/api/v1/../actuator/health", filter)).isEqualTo(HttpServletResponse.SC_OK);
        clearAuth();
    }

    // ---------- Spec 69 (critério 11): rotas MASTER/ADMIN também colapsam por prefixo ----------

    @Test
    void masterResourceIdsShareTheSameBucketPerPrincipal() throws Exception {
        // Spec 69 (critério 11 — fecha o bypass de cardinalidade do SEC-23 no
        // lado master): /api/v1/master/merchants/{id}/..., /master/receivables/{id}/...
        // e /master/profiles/{id}/... tinham {id} SEM colapso — um master criava N
        // lojistas/recursos → N buckets do limite por PRINCIPAL → vazão N × teto.
        // Agora os prefixos /api/v1/master e /api/v1/admin colapsam por rota.
        UUID masterId = UUID.randomUUID();
        authenticate(masterId);
        AuthenticatedRateLimitFilter filter = filter(2, true, true);

        // 3 ids distintos da MESMA rota de recurso do master → mesmo bucket → 3ª vira 429.
        assertThat(call("POST", "/api/v1/master/merchants/111/suspend", filter))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/master/merchants/222/suspend", filter))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/master/merchants/333/suspend", filter)).isEqualTo(429);

        // Outra rota de recurso do master (receivables/{id}/write-off) também
        // colapsa no MESMO prefixo /api/v1/master — bucket da rota, sem
        // cardinalidade por id.
        assertThat(call("POST", "/api/v1/master/receivables/aaa/write-off", filter))
                .isEqualTo(429);
        clearAuth();
    }

    @Test
    void masterResourceKeyUsesPrefixNotFullUri() throws Exception {
        // Verifica a chave exata do store para rota de recurso do master:
        // prefixo fixo /api/v1/master (não a URI com o id do recurso).
        UUID masterId = UUID.randomUUID();
        authenticate(masterId);
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(properties, objectMapper, store, securityAlerts, merchantConfigProvider, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/master/merchants/" + UUID.randomUUID() + "/withdrawals/block");
        filter.doFilter(request, response, new MockFilterChain());

        verify(store).tryAcquire("/api/v1/master|" + masterId, 120, 60L);
        clearAuth();
    }

    @Test
    void adminRoutesCollapseByPrefixPerPrincipal() throws Exception {
        // /api/v1/admin/{...} também entra no colapso (SEC-23 no master, Spec 69):
        // o bucket reflete a rota, não o recurso.
        UUID masterId = UUID.randomUUID();
        authenticate(masterId);
        AuthenticatedRateLimitFilter filter = filter(1, true, true);

        assertThat(call("GET", "/api/v1/admin/merchants/1", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("GET", "/api/v1/admin/merchants/2", filter)).isEqualTo(429);
        clearAuth();
    }

    // ---------- Spec 98 (D4.3): override rps/rpm por lojista (fail-closed) ----------

    @Test
    void rpsOverrideUsesOneSecondWindow() throws Exception {
        // rate_limit_rps = 1 → janela de 1s; a 2ª chamada no mesmo segundo → 429
        // (mais restritivo que o teto global de 120/min, por isso o teste usa 120).
        UUID merchantId = UUID.randomUUID();
        when(merchantConfigProvider.rateLimit(merchantId))
                .thenReturn(Optional.of(new com.foxcash.domain.port.out.MerchantConfigProvider.RateLimitOverride(1, null)));

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        properties.getAuthenticated().setWindowSeconds(60);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(
                properties, objectMapper, new InMemoryRateLimitStore(Clock.systemUTC()),
                securityAlerts, merchantConfigProvider, meterRegistry);

        authenticate(merchantId);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(429);
        clearAuth();
    }

    @Test
    void rpmOverrideUsesSixtySecondWindow() throws Exception {
        // rate_limit_rpm = 2 → janela de 60s; a 3ª chamada → 429.
        UUID merchantId = UUID.randomUUID();
        when(merchantConfigProvider.rateLimit(merchantId))
                .thenReturn(Optional.of(new com.foxcash.domain.port.out.MerchantConfigProvider.RateLimitOverride(null, 2)));

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        properties.getAuthenticated().setWindowSeconds(60);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(
                properties, objectMapper, new InMemoryRateLimitStore(Clock.systemUTC()),
                securityAlerts, merchantConfigProvider, meterRegistry);

        authenticate(merchantId);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(429);
        clearAuth();
    }

    @Test
    void noOverrideFallsBackToGlobal() throws Exception {
        // Fail-closed (decisão de segurança): sem override → teto global (nunca
        // menos restritivo por acidente). 2 chamadas com teto global 120 → 200.
        UUID merchantId = UUID.randomUUID();
        when(merchantConfigProvider.rateLimit(merchantId)).thenReturn(Optional.empty());

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        properties.getAuthenticated().setWindowSeconds(60);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(
                properties, objectMapper, new InMemoryRateLimitStore(Clock.systemUTC()),
                securityAlerts, merchantConfigProvider, meterRegistry);

        authenticate(merchantId);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(call("POST", "/api/v1/foxcash/pix/cob", filter)).isEqualTo(HttpServletResponse.SC_OK);
        clearAuth();
    }

    // ---------- retomada (Spec 58 + Spec 64 + Spec 98): alerta, chain e override só-lojista ----------

    @Test
    void blockedRequestRecordsSecurityBurst() throws Exception {
        // L108 (VoidMethodCallMutator): o 429 por PRINCIPAL alerta a rajada
        // (Spec 64) — remover o recordRateLimitBurst silenciaria o abuso.
        authenticate(UUID.randomUUID());
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(
                properties, objectMapper, store, securityAlerts, merchantConfigProvider, meterRegistry);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/foxcash/pix/cob");
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        verify(securityAlerts).recordRateLimitBurst("authenticated");
        clearAuth();
    }

    @Test
    void passesRequestToChainWhenStoreAllows() throws Exception {
        // L116 (VoidMethodCallMutator): chain.doFilter NÃO pode ser removido —
        // quando o store permite, a requisição prossegue na cadeia.
        authenticate(UUID.randomUUID());
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(
                properties, objectMapper, store, securityAlerts, merchantConfigProvider, meterRegistry);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/foxcash/pix/cob");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        clearAuth();
    }

    @Test
    void nonMerchantPrincipalIgnoresMerchantRateLimitOverride() throws Exception {
        // L127 (BooleanTrueReturnValsMutator): `isMerchant` trocado por sempre-true
        // — um principal MASTER (UUID) passaria a aplicar o override de rate limit
        // do LOJISTA. O override é exclusivo do ROLE_MERCHANT (fail-closed:
        // não-lojista → teto global, nunca menos restritivo por acidente).
        UUID masterId = UUID.randomUUID();
        when(merchantConfigProvider.rateLimit(masterId))
                .thenReturn(Optional.of(new com.foxcash.domain.port.out.MerchantConfigProvider.RateLimitOverride(5, null)));
        authenticate(masterId, "ROLE_MASTER");
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);

        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.getAuthenticated().setEnabled(true);
        properties.getAuthenticated().setMaxRequests(120);
        properties.getAuthenticated().setWindowSeconds(60);
        AuthenticatedRateLimitFilter filter = new AuthenticatedRateLimitFilter(
                properties, objectMapper, store, securityAlerts, merchantConfigProvider, meterRegistry);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/foxcash/pix/cob");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // Original: master NÃO é lojista → override ignorado → teto global (120, 60s).
        // Mutante: isMerchant=true → override rps=5 → janela 1s → tryAcquire(..., 5, 1).
        verify(store).tryAcquire("/api/v1/foxcash/pix/cob|" + masterId, 120, 60L);
        clearAuth();
    }
}
