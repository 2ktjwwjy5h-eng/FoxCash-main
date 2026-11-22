package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.ApiKeyStatus;
import com.foxcash.domain.model.apikey.MerchantApiKey;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.out.MerchantApiKeyRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link ApiKeyAuthFilter} (Spec 12 + Spec 24): resolve X-Api-Key →
 * ROLE_ADMIN (chave admin), ROLE_MERCHANT (chave gerenciável ACTIVE com escopo)
 * ou fallback legado (merchants.api_key_hash); chave revogada/sem escopo →
 * não autentica (401/403).
 */
class ApiKeyAuthFilterTest {

    private static final String ADMIN_KEY = "admin-secret-key";
    private static final String MERCHANT_KEY = "mch_0123456789abcdef0123456789abcdef";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);

    private final MerchantRepository repository = mock(MerchantRepository.class);
    private final MerchantApiKeyRepository apiKeyRepository = mock(MerchantApiKeyRepository.class);
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(repository, apiKeyRepository, ADMIN_KEY, CLOCK,
                new ObjectMapper(), new ApiKeySecurityProperties());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private Authentication authenticate(String path, String apiKey) throws Exception {
        return authenticateWith(filter, path, apiKey);
    }

    private Authentication authenticateWith(ApiKeyAuthFilter authFilter, String path, String apiKey)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request(path);
        if (apiKey != null) {
            request.addHeader("X-Api-Key", apiKey);
        }

        authFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private Merchant activeMerchant() {
        return Merchant.register("Loja A", "loja@example.com",
                PricingPlan.fixed(), DocumentHasher.hash(MERCHANT_KEY));
    }

    /** Chave gerenciável ativa com escopos informados (hash da chave de teste). */
    private MerchantApiKey managedKey(UUID merchantId, ApiKeyScope... scopes) {
        return MerchantApiKey.restore(UUID.randomUUID(), merchantId, "Bot",
                "mch_012345", DocumentHasher.hash(MERCHANT_KEY), ApiKeyStatus.ACTIVE,
                Set.of(scopes), null, CLOCK.instant());
    }

    @Test
    void adminKeyGrantsAdminRole() throws Exception {
        Authentication auth = authenticate("/api/v1/admin/merchants", ADMIN_KEY);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void managedKeyWithTotalScopeGrantsMerchantRoleWithMerchantIdPrincipal() throws Exception {
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.values())));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        Authentication auth = authenticate("/api/v1/merchants/" + merchant.id(), MERCHANT_KEY);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_MERCHANT");
        assertThat(auth.getPrincipal()).isEqualTo(merchant.id());
        // L141: autenticado pela chave gerenciável → NÃO cai no fallback legado.
        verify(repository, never()).findByApiKeyHash(anyString());
    }

    @Test
    void managedKeyWithMatchingScopeGrantsMerchantRole() throws Exception {
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.TRANSACTIONS)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        Authentication auth = authenticate("/api/v1/transactions", MERCHANT_KEY);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_MERCHANT");
    }

    @Test
    void managedKeyWithoutRequiredScopeReturns403() throws Exception {
        Merchant merchant = activeMerchant();
        // Chave com escopo TRANSACTIONS tentando criar cobrança (charges).
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.TRANSACTIONS)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/api/v1/foxcash/pix/cob");
        request.addHeader("X-Api-Key", MERCHANT_KEY);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("forbidden");
        // L173: setContentType não pode ser removido (senão contentType fica null).
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        // L177: flushBuffer commita a resposta — o chain NÃO pode continuar.
        assertThat(chain.getRequest()).isNull();
        // L135: chave gerenciável encontrada (403 por escopo) → NÃO cai no fallback legado.
        verify(repository, never()).findByApiKeyHash(anyString());
    }

    @Test
    void scopedKeyDoesNotInheritScopeFromSimilarPathPrefix() throws Exception {
        // Auditoria 2026-08-05 (B-A7): antes path.contains("/transactions")
        // casava QUALQUER substring — uma rota /transactionsX-report herdaria o
        // escopo TRANSACTIONS. Agora o casamento é por prefixo de segmento: uma
        // rota parecida exige escopo total → 403 para chave com escopo apenas.
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.TRANSACTIONS)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/api/v1/transactionsX-report");
        request.addHeader("X-Api-Key", MERCHANT_KEY);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void chargesScopedKeyGrantsChargesRoute() throws Exception {
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.CHARGES)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        Authentication auth = authenticate("/api/v1/foxcash/pix/cob", MERCHANT_KEY);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_MERCHANT");
    }

    @Test
    void normalizedPathCannotBypassScopeWithDotDotSegments() throws Exception {
        // SEC-05 (revisão de segurança 2026-08-11): o escopo é decidido sobre o
        // path NORMALIZADO (StringUtils.cleanPath colapsa `..`). Uma URI como
        // /api/v1/foxcash/pix/../transactions resolve para /api/v1/transactions
        // (escopo TRANSACTIONS) — uma chave só-CHARGES NÃO pode burlar via `..`.
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.CHARGES)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/api/v1/foxcash/pix/../transactions");
        request.addHeader("X-Api-Key", MERCHANT_KEY);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void normalizedPathStillGrantsChargesForDotSegment() throws Exception {
        // SEC-05: um segmento `.` dentro da rota de charges é colapsado sem
        // mudar o escopo (./cob → /cob — CHARGES mantido, sem regressão).
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.CHARGES)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        Authentication auth = authenticate("/api/v1/foxcash/pix/./cob", MERCHANT_KEY);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_MERCHANT");
    }

    @Test
    void managedKeyWithoutTotalScopeOnUnmappedRouteReturns403() throws Exception {
        Merchant merchant = activeMerchant();
        // Rota sem escopo mapeado (ex.: billing) exige escopo total.
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.CHARGES)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/api/v1/billing/preview");
        request.addHeader("X-Api-Key", MERCHANT_KEY);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void revokedKeyIsNotAuthenticated() throws Exception {
        MerchantApiKey revoked = managedKey(activeMerchant().id(), ApiKeyScope.values());
        revoked.revoke();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(revoked));

        Authentication auth = authenticate("/api/v1/transactions", MERCHANT_KEY);

        assertThat(auth).isNull();
        // L122: chave revogada → NÃO cai no fallback legado.
        verify(repository, never()).findByApiKeyHash(anyString());
    }

    @Test
    void managedKeyWithInactiveMerchantIsNotAuthenticated() throws Exception {
        Merchant suspended = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199",
                DocumentHasher.hash(MERCHANT_KEY), MerchantStatus.SUSPENDED, PricingPlan.fixed(),
                com.foxcash.domain.model.Money.of("0.00"), false, Instant.now());
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(suspended.id(), ApiKeyScope.values())));
        when(repository.findById(suspended.id())).thenReturn(Optional.of(suspended));

        Authentication auth = authenticate("/api/v1/transactions", MERCHANT_KEY);

        assertThat(auth).isNull();
        // L129: lojista inativo → NÃO cai no fallback legado.
        verify(repository, never()).findByApiKeyHash(anyString());
    }

    @Test
    void unknownManagedKeyFallsBackToLegacyMerchantKey() throws Exception {
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        when(repository.findByApiKeyHash(anyString())).thenReturn(Optional.of(merchant));

        Authentication auth = authenticate("/api/v1/transactions", MERCHANT_KEY);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_MERCHANT");
        assertThat(auth.getPrincipal()).isEqualTo(merchant.id());
    }

    @Test
    void unknownKeyIsNotAuthenticated() throws Exception {
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        when(repository.findByApiKeyHash(anyString())).thenReturn(Optional.empty());

        Authentication auth = authenticate("/api/v1/transactions", "mch_ffffffffffffffffffffffffffffffff");

        assertThat(auth).isNull();
    }

    @Test
    void missingKeyIsNotAuthenticated() throws Exception {
        Authentication auth = authenticate("/api/v1/transactions", null);

        assertThat(auth).isNull();
    }

    @Test
    void validAuthenticationRecordsLastUse() throws Exception {
        Merchant merchant = activeMerchant();
        UUID keyId = UUID.randomUUID();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(
                MerchantApiKey.restore(keyId, merchant.id(), "Bot", "mch_012345",
                        DocumentHasher.hash(MERCHANT_KEY), ApiKeyStatus.ACTIVE,
                        Set.of(ApiKeyScope.TRANSACTIONS), null, CLOCK.instant())));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        authenticate("/api/v1/transactions", MERCHANT_KEY);

        verify(apiKeyRepository).recordUse(keyId, CLOCK.instant());
    }

    @Test
    void withdrawalsScopedKeyGrantsWithdrawalsRoute() throws Exception {
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.WITHDRAWALS)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        Authentication auth = authenticate("/api/v1/merchants/" + merchant.id() + "/withdrawals", MERCHANT_KEY);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_MERCHANT");
    }

    @Test
    void withdrawalsRouteRequiresWithdrawalsScope() throws Exception {
        Merchant merchant = activeMerchant();
        // Chave com escopo CHARGES (não WITHDRAWALS) → 403 na rota de saques.
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.CHARGES)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("/api/v1/merchants/" + merchant.id() + "/withdrawals");
        request.addHeader("X-Api-Key", MERCHANT_KEY);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("forbidden");
    }

    // ---------- Spec 65 (Fase 1): throttle do last_used_at + merchant no request ----------

    @Test
    void recordUseIsThrottledWithinInterval() throws Exception {
        // 2 requisicoes da MESMA chave dentro do intervalo (relogio fixo) → o
        // UPDATE de last_used_at roda apenas 1x (Spec 65: no max 1x/min/chave).
        Merchant merchant = activeMerchant();
        UUID keyId = UUID.randomUUID();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(
                MerchantApiKey.restore(keyId, merchant.id(), "Bot", "mch_012345",
                        DocumentHasher.hash(MERCHANT_KEY), ApiKeyStatus.ACTIVE,
                        Set.of(ApiKeyScope.TRANSACTIONS), null, CLOCK.instant())));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        authenticate("/api/v1/transactions", MERCHANT_KEY);
        authenticate("/api/v1/transactions", MERCHANT_KEY);

        verify(apiKeyRepository, times(1)).recordUse(eq(keyId), any(Instant.class));
    }

    @Test
    void recordUseResumesAfterInterval() throws Exception {
        // Relogio avancando: 1o uso grava; apos 61s o proximo uso grava de novo.
        MutableClock clock = new MutableClock(Instant.parse("2026-08-04T12:00:00Z"));
        ApiKeyAuthFilter advancingFilter = new ApiKeyAuthFilter(
                repository, apiKeyRepository, ADMIN_KEY, clock, new ObjectMapper(),
                new ApiKeySecurityProperties());
        Merchant merchant = activeMerchant();
        UUID keyId = UUID.randomUUID();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(
                MerchantApiKey.restore(keyId, merchant.id(), "Bot", "mch_012345",
                        DocumentHasher.hash(MERCHANT_KEY), ApiKeyStatus.ACTIVE,
                        Set.of(ApiKeyScope.TRANSACTIONS), null, clock.instant())));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        authenticateWith(advancingFilter, "/api/v1/transactions", MERCHANT_KEY);
        verify(apiKeyRepository, times(1)).recordUse(eq(keyId), any(Instant.class));

        clock.set(clock.instant().plusSeconds(61));
        authenticateWith(advancingFilter, "/api/v1/transactions", MERCHANT_KEY);

        verify(apiKeyRepository, times(2)).recordUse(eq(keyId), any(Instant.class));
    }

    @Test
    void managedKeyExposesResolvedMerchantOnRequest() throws Exception {
        // Spec 65 (Fase 1): o lojista carregado pelo filtro fica no request —
        // o service do charge reusa a MESMA entidade (sem reload = -1 SELECT).
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.CHARGES)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        MockHttpServletRequest request = request("/api/v1/foxcash/pix/cob");
        request.addHeader("X-Api-Key", MERCHANT_KEY);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(AuthenticatedMerchantResolver.MERCHANT_ATTRIBUTE))
                .isSameAs(merchant);
    }

    // ---------- retomada (ddf6905 + Spec 98): atributo no fallback legado + poda do throttle ----------

    @Test
    void legacyKeyExposesResolvedMerchantOnRequest() throws Exception {
        // L124 (VoidMethodCallMutator): o fallback legado (Spec 12) também expõe
        // o lojista no request (Spec 65 — o service do charge reusa a MESMA
        // entidade, sem reload). Remover o setAttribute quebraria o contrato.
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        when(repository.findByApiKeyHash(anyString())).thenReturn(Optional.of(merchant));

        MockHttpServletRequest request = request("/api/v1/transactions");
        request.addHeader("X-Api-Key", MERCHANT_KEY);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(AuthenticatedMerchantResolver.MERCHANT_ATTRIBUTE))
                .isSameAs(merchant);
    }

    @Test
    void recordUseThrottledAcrossThreeRequestsWithinInterval() throws Exception {
        // L205 (NullReturnValsMutator): `return last` trocado por `return null` —
        // o compute removeria a entrada do mapa e o 3º request na MESMA janela
        // voltaria a gravar. O throttle deve persistir: 3 requests na janela → 1
        // único UPDATE de last_used_at.
        Merchant merchant = activeMerchant();
        UUID keyId = UUID.randomUUID();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(
                MerchantApiKey.restore(keyId, merchant.id(), "Bot", "mch_012345",
                        DocumentHasher.hash(MERCHANT_KEY), ApiKeyStatus.ACTIVE,
                        Set.of(ApiKeyScope.TRANSACTIONS), null, CLOCK.instant())));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        authenticate("/api/v1/transactions", MERCHANT_KEY);
        authenticate("/api/v1/transactions", MERCHANT_KEY);
        authenticate("/api/v1/transactions", MERCHANT_KEY);

        verify(apiKeyRepository, times(1)).recordUse(eq(keyId), any(Instant.class));
    }

    /** Acesso por reflexão ao mapa do throttle (estado por-instância privado). */
    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<UUID, Instant> throttleMap() throws Exception {
        Field field = ApiKeyAuthFilter.class.getDeclaredField("lastRecordedUse");
        field.setAccessible(true);
        return (ConcurrentHashMap<UUID, Instant>) field.get(filter);
    }

    private int maxTrackedKeys() throws Exception {
        Field field = ApiKeyAuthFilter.class.getDeclaredField("MAX_TRACKED_KEYS");
        field.setAccessible(true);
        return field.getInt(null);
    }

    @Test
    void pruneRemovesStaleEntriesWhenOverMaxTrackedKeys() throws Exception {
        // L211 (VoidMethodCallMutator: pruneIfNeeded removido) + L218
        // (NegateConditionalsMutator `size > MAX` → `size <= MAX`): acima do teto
        // a poda REMOVE entradas vencidas — sem a poda (ou com a condição
        // negada) as entradas stale permaneceriam no mapa.
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.TRANSACTIONS)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        ConcurrentHashMap<UUID, Instant> map = throttleMap();
        Instant stale = Instant.parse("2020-01-01T00:00:00Z");
        for (int i = 0; i < maxTrackedKeys() + 1; i++) {
            map.put(UUID.randomUUID(), stale);
        }

        authenticate("/api/v1/transactions", MERCHANT_KEY); // 1ª chave nova → poda

        // L211/L218-negate: sem poda as stale permaneceriam (size >> 1).
        // L220 (BooleanTrueReturnValsMutator no removeIf): o predicado sempre-true
        // removeria TAMBÉM a entrada da chave nova (mapa vazio) — a poda correta
        // remove só as vencidas e mantém a chave recém-utilizada.
        assertThat(map).hasSize(1);
    }

    @Test
    void pruneDoesNotRunExactlyAtMaxTrackedKeys() throws Exception {
        // L218 (ConditionalsBoundaryMutator `size > MAX` → `size >= MAX`): com
        // EXATAMENTE MAX entradas a poda NÃO roda (o teto é estrito >, não >=) —
        // as entradas stale permanecem.
        Merchant merchant = activeMerchant();
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(managedKey(merchant.id(), ApiKeyScope.TRANSACTIONS)));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        ConcurrentHashMap<UUID, Instant> map = throttleMap();
        Instant stale = Instant.parse("2020-01-01T00:00:00Z");
        for (int i = 0; i < maxTrackedKeys() - 1; i++) {
            map.put(UUID.randomUUID(), stale);
        }

        authenticate("/api/v1/transactions", MERCHANT_KEY); // +1 = exatamente MAX

        assertThat(map.values()).anyMatch(value -> value.equals(stale));
    }

    @Test
    void pruneNotCalledForRepeatedUseWithinIntervalOverMax() throws Exception {
        // L210 (NegateConditionalsMutator `if (record[0])` → `if (!record[0])`):
        // um uso dentro da janela (record[0]==false) NÃO deve disparar a poda —
        // com o mapa acima do teto, as entradas stale permanecem. O mutante
        // podaria (e removeria) no uso repetido.
        Merchant merchant = activeMerchant();
        UUID keyId = UUID.randomUUID();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(
                MerchantApiKey.restore(keyId, merchant.id(), "Bot", "mch_012345",
                        DocumentHasher.hash(MERCHANT_KEY), ApiKeyStatus.ACTIVE,
                        Set.of(ApiKeyScope.TRANSACTIONS), null, CLOCK.instant())));
        when(repository.findById(merchant.id())).thenReturn(Optional.of(merchant));

        ConcurrentHashMap<UUID, Instant> map = throttleMap();
        Instant stale = Instant.parse("2020-01-01T00:00:00Z");
        map.put(keyId, CLOCK.instant()); // uso recente dentro da janela
        for (int i = 0; i < maxTrackedKeys(); i++) {
            map.put(UUID.randomUUID(), stale);
        }

        authenticate("/api/v1/transactions", MERCHANT_KEY); // dentro da janela → sem poda

        assertThat(map.values()).anyMatch(value -> value.equals(stale));
    }

    /** Relogio mutavel para testar o throttle (Spec 65). */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
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
}
