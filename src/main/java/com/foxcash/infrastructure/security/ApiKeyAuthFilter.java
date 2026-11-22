package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.MerchantApiKey;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.port.out.MerchantApiKeyRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autenticacao da API por API key (Spec 12 + Spec 24, ADR-006).
 *
 * <p>Resolve o header {@code X-Api-Key}:
 * <ul>
 *   <li>Igual a chave admin ({@code foxcash.security.admin-api-key} /
 *       {@code FOXCASH_ADMIN_API_KEY}) → {@code ROLE_ADMIN};</li>
 *   <li>Senao, calcula o SHA-256 e busca a chave em {@code merchant_api_keys}
 *       (Spec 24 — chaves gerenciáveis do portal). Chave {@code ACTIVE} +
 *       lojista {@code ACTIVE} → {@code ROLE_MERCHANT} com o {@code merchantId}
 *       como principal, e registra {@code last_used_at}. Chave valida mas com
 *       escopo insuficiente para a rota → <b>403 {@code forbidden}</b> direto
 *       (o SecurityConfig nao distingue escopo — excecao documentada).</li>
 *   <li>Fallback legado (Spec 12): chave unica do cadastro em
 *       {@code merchants.api_key_hash} (anterior a Spec 24) com escopo total.</li>
 *   <li>Caso contrario, nenhuma autenticacao — as rotas protegidas respondem
 *       401 e o webhook/cadastro (publicos) seguem sem autenticacao.</li>
 * </ul>
 *
 * <p>Nunca bloqueia a requisicao sozinho (exceto o 403 de escopo, acima):
 * apenas preenche o {@link SecurityContextHolder}; a autorizacao (401/403 de
 * role) e responsabilidade do {@link SecurityConfig}. O caminho
 * {@code /webhooks/pix} e publico no Spring Security (a assinatura HMAC da
 * Spec 10 e a autenticacao do webhook).
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";

    private final MerchantRepository merchantRepository;
    private final MerchantApiKeyRepository merchantApiKeyRepository;
    private final String adminApiKey;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ApiKeySecurityProperties apiKeySecurityProperties;

    /**
     * Throttle do {@code last_used_at} (Spec 65 — Fase 1): ultimo instante em que
     * o UPDATE de uso foi gravado por chave. O UPDATE roda no maximo 1x por
     * {@code record-use-interval} por chave (por instancia) — o hot spot de
     * escrita do caminho do charge (pg_stat_statements: 98.710 chamadas, media
     * 41ms) vira ~1/min/chave. Mapa TTL-bounded (podado quando passa o limite de
     * entradas) — estado em memoria inventariado na Spec 65/61.
     */
    private final ConcurrentHashMap<UUID, Instant> lastRecordedUse = new ConcurrentHashMap<>();

    /** Teto de chaves rastreadas no mapa do throttle (podas alem disso). */
    private static final int MAX_TRACKED_KEYS = 10_000;

    public ApiKeyAuthFilter(MerchantRepository merchantRepository,
                            MerchantApiKeyRepository merchantApiKeyRepository,
                            String adminApiKey,
                            Clock clock,
                            ObjectMapper objectMapper,
                            ApiKeySecurityProperties apiKeySecurityProperties) {
        this.merchantRepository = merchantRepository;
        this.merchantApiKeyRepository = merchantApiKeyRepository;
        this.adminApiKey = adminApiKey;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.apiKeySecurityProperties = apiKeySecurityProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey != null && !apiKey.isBlank()) {
            // Comparação em tempo constante (MessageDigest.isEqual) — evita
            // timing attack na chave admin (QA 2026-08-04).
            if (adminApiKey != null && !adminApiKey.isBlank()
                    && MessageDigest.isEqual(
                            adminApiKey.getBytes(StandardCharsets.UTF_8),
                            apiKey.getBytes(StandardCharsets.UTF_8))) {
                setAuthentication("admin", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            } else if (!authenticateWithManagedKey(request, response, apiKey)) {
                // Fallback legado (Spec 12): chave única do cadastro em
                // merchants.api_key_hash — escopo total implícito.
                merchantRepository.findByApiKeyHash(DocumentHasher.hash(apiKey))
                        .filter(ApiKeyAuthFilter::isActive)
                        .ifPresent(merchant -> {
                            try {
                                // Spec 98 (T3.4): api_blocked → 403 antes de autenticar.
                                if (merchant.config().apiBlocked()) {
                                    writeApiBlocked(response);
                                    return;
                                }
                                setAuthentication(
                                        merchant.id(),
                                        List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
                                // Spec 65 (Fase 1): o lojista ja foi carregado aqui —
                                // o service do charge reusa esta entidade (sem reload).
                                request.setAttribute(
                                        AuthenticatedMerchantResolver.MERCHANT_ATTRIBUTE, merchant);
                            } catch (IOException e) {
                                // Falha ao escrever a resposta (writer) — não propaga checked
                                // do lambda; o container encerra a cadeia com 500.
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
            }
        }

        // Se o filtro já respondeu (403 de escopo), encerra a cadeia — o Spring
        // Security não deve sobrescrever a resposta com 401.
        if (!response.isCommitted()) {
            chain.doFilter(request, response);
        }
    }

    /**
     * Tenta autenticar com a chave gerenciável (Spec 24). Retorna {@code true}
     * quando a chave foi encontrada e processada (autenticada, 403 por escopo
     * ou revogada) — ou seja, o fallback legado não deve rodar.
     */
    private boolean authenticateWithManagedKey(HttpServletRequest request, HttpServletResponse response,
                                               String apiKey) throws IOException {
        Optional<MerchantApiKey> managedKey =
                merchantApiKeyRepository.findByKeyHash(DocumentHasher.hash(apiKey));
        if (managedKey.isEmpty()) {
            return false;
        }
        MerchantApiKey key = managedKey.get();

        // Chave revogada → não autentica (401 pelo SecurityConfig).
        if (!key.isActive()) {
            return true;
        }

        // Lojista dono da chave deve estar ACTIVE (Spec 12).
        Optional<Merchant> merchant = merchantRepository.findById(key.merchantId())
                .filter(ApiKeyAuthFilter::isActive);
        if (merchant.isEmpty()) {
            return true;
        }

        // Spec 98 (T3.4): api_blocked → 403 ANTES dos escopos (o portal/sessão
        // não usa API key — segue funcionando).
        if (merchant.get().config().apiBlocked()) {
            writeApiBlocked(response);
            return true;
        }

        // Escopo: rota sem escopo mapeado exige escopo total.
        if (!hasScopeForPath(key, request.getRequestURI())) {
            writeForbidden(response);
            return true;
        }

        // Spec 65 (Fase 1): o lojista ja foi carregado acima — o service do
        // charge reusa esta entidade via AuthenticatedMerchantResolver (sem
        // reload no service). O throttle limita o UPDATE de last_used_at a
        // ~1/min/chave (o hot spot de escrita do caminho do charge).
        request.setAttribute(AuthenticatedMerchantResolver.MERCHANT_ATTRIBUTE, merchant.get());
        Instant now = clock.instant();
        if (shouldRecordUse(key.id(), now)) {
            merchantApiKeyRepository.recordUse(key.id(), now);
        }
        setAuthentication(merchant.get().id(),
                List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        return true;
    }

    /**
     * Throttle do {@code last_used_at} (Spec 65): {@code true} apenas quando o
     * intervalo ({@code foxcash.security.api-key.record-use-interval}, default
     * 60s) ja decorreu desde o ultimo UPDATE da MESMA chave — atomico por chave
     * (o primeiro uso de uma chave sempre grava).
     */
    private boolean shouldRecordUse(UUID keyId, Instant now) {
        boolean[] record = {false};
        lastRecordedUse.compute(keyId, (id, last) -> {
            if (last != null && now.isBefore(last.plus(apiKeySecurityProperties.getRecordUseInterval()))) {
                return last; // ainda dentro do intervalo — sem novo UPDATE
            }
            record[0] = true;
            return now;
        });
        if (record[0]) {
            pruneIfNeeded(now);
        }
        return record[0];
    }

    /** Podas o mapa do throttle quando passa do teto (remove entradas ja vencidas). */
    private void pruneIfNeeded(Instant now) {
        if (lastRecordedUse.size() > MAX_TRACKED_KEYS) {
            Instant cutoff = now.minus(apiKeySecurityProperties.getRecordUseInterval());
            lastRecordedUse.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }
    }

    /** A chave pode acessar a rota? (escopo da rota ⊆ escopos da chave). */
    private boolean hasScopeForPath(MerchantApiKey key, String path) {
        ApiKeyScope required = requiredScope(path);
        return required == null ? key.hasTotalScope() : key.hasScope(required);
    }

    /**
     * Escopo necessário para a rota (ou {@code null} — nenhum específico,
     * exige escopo total). Rotas do portal ({@code /portal/**}) usam sessão e
     * não passam por esta validação (role MERCHANT_PORTAL).
     */
    private ApiKeyScope requiredScope(String path) {
        // Auditoria 2026-08-05 (B-A7): casamento por PREFIXO de segmento (antes
        // path.contains() — uma futura rota /transactions-report herdaria o escopo).
        // SEC-05 (revisão de segurança 2026-08-11): o path é NORMALIZADO com
        // StringUtils.cleanPath (colapsa `..`, `.` e // duplicadas) — o escopo é
        // decidido na MESMA forma que o container/router enxerga a rota. Antes,
        // uma URI com `..` (ex.: /api/v1/foxcash/pix/../transactions) era avaliada
        // com o path cru e podia receber escopo errado (bypass de escopo).
        String normalized = org.springframework.util.StringUtils.cleanPath(path);
        if (normalized.startsWith("/api/v1/foxcash/pix/")) {
            return ApiKeyScope.CHARGES;
        }
        if (normalized.equals("/api/v1/transactions")
                || normalized.startsWith("/api/v1/transactions/")) {
            return ApiKeyScope.TRANSACTIONS;
        }
        if (normalized.startsWith("/api/v1/merchants/") && normalized.contains("/withdrawals")) {
            return ApiKeyScope.WITHDRAWALS;
        }
        return null;
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponse.of("forbidden", "Chave sem escopo para esta rota")));
        // Commita a resposta — o Spring Security não deve sobrescrever com 401.
        response.flushBuffer();
    }

    /** Spec 98 (T3.4): API key do lojista bloqueada pelo master (403 api_blocked). */
    private void writeApiBlocked(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponse.of("api_blocked", "API key do lojista bloqueada")));
        response.flushBuffer();
    }

    private static boolean isActive(Merchant merchant) {
        return merchant.status() == MerchantStatus.ACTIVE;
    }

    private static void setAuthentication(Object principal, List<SimpleGrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
