package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.domain.port.out.RateLimitStore;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Rate limiting por PRINCIPAL autenticado (Spec 58 — achado CRÍTICO da análise
 * de resiliência 2026-08-07: o rate limit existente é por IP e não enxerga o
 * usuário; um lojista com API key pode gerar carga ilimitada no core).
 *
 * <p>Janela fixa por {@code path|principal} (merchantId, masterId ou "admin"),
 * reutilizando a mesma {@link RateLimitStore} (memory/redis, Spec 43) e o mesmo
 * contrato 429 {@code too_many_requests} do {@link RateLimitFilter}. A chave é
 * por PRINCIPAL (não por chave individual) — o objetivo é limitar o LOJISTA:
 * múltiplas chaves da mesma conta compartilham o mesmo bucket.
 *
 * <p>Ordem dos filtros (crítica): registrado com
 * {@code addFilterAfter(..., ApiKeyAuthFilter.class)} no {@link SecurityConfig}
 * — roda DEPOIS dos filtros de autenticação, quando o {@link SecurityContextHolder}
 * já contém o principal. Requests anônimos (sem principal) são ignorados
 * (seguem o fluxo existente, coberto pelo {@link RateLimitFilter} por IP).
 * Exclui actuator/health (público ao orquestrador). {@code OncePerRequestFilter}
 * garante uma única execução por request (sem dupla contagem).
 */
@Component
public class AuthenticatedRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final RateLimitStore store;
    private final SecurityAlertPublisher securityAlerts;
    // Spec 98 (D4.3): override rps/rpm por lojista — fail-closed (ausente → global).
    private final com.foxcash.domain.port.out.MerchantConfigProvider merchantConfigProvider;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public AuthenticatedRateLimitFilter(RateLimitProperties properties,
                                        ObjectMapper objectMapper,
                                        RateLimitStore store,
                                        SecurityAlertPublisher securityAlerts,
                                        com.foxcash.domain.port.out.MerchantConfigProvider merchantConfigProvider,
                                        io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.store = store;
        this.securityAlerts = securityAlerts;
        this.merchantConfigProvider = merchantConfigProvider;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Filtro desligado (dev/tests) → não filtra.
        if (!properties.isEnabled() || !properties.getAuthenticated().isEnabled()) {
            return true;
        }
        // Só rotas da API (o actuator/health é público ao orquestrador).
        // SEC-16: path normalizado — /api/v1/../actuator resolve para /actuator
        // (público) e não pode ser tratado como rota da API (rate limit
        // desnecessário/errado por path traversal).
        if (!org.springframework.util.StringUtils.cleanPath(request.getRequestURI()).startsWith("/api/v1/")) {
            return true;
        }
        // Sem principal autenticado (anônimo) → segue o fluxo existente
        // (RateLimitFilter por IP cobre as rotas públicas).
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = String.valueOf(authentication.getPrincipal());
        // SEC-02: a chave usa a rota NORMALIZADA (prefixo fixo) — cada {id} no
        // path não pode criar bucket novo (bypass do teto por cardinalidade).
        // SEC-16: o path também passa por StringUtils.cleanPath (colapsa `..`,
        // `.` e //) — uma URI com `..` não pode criar bucket de rota distinta
        // (o mesmo principal contornaria o teto alternando paths com `..`).
        String path = org.springframework.util.StringUtils.cleanPath(request.getRequestURI());
        String key = RateLimitKey.normalize(path) + "|" + principal;

        RateLimitProperties.Authenticated config = properties.getAuthenticated();
        // Spec 98 (D4.3): override de rate limit por LOJISTA (rps/rpm) — o
        // principal ROLE_MERCHANT consulta o override antes do teto global.
        // Fail-closed: sem override / principal não-lojista → teto global (nunca
        // menos restritivo por acidente — decisão de segurança).
        int maxRequests = config.getMaxRequests();
        int windowSeconds = config.getWindowSeconds();
        RateLimitWindow override = merchantOverride(authentication, principal);
        if (override != null) {
            maxRequests = override.maxRequests;
            windowSeconds = override.windowSeconds;
        }
        if (!store.tryAcquire(key, maxRequests, windowSeconds)) {
            // Spec 64: rajada de 429 por principal → alerta de segurança (throttled).
            securityAlerts.recordRateLimitBurst("authenticated");
            // Spec 126: métrica de 429 (grupo "authenticated" — alerta proativo de abuso).
            meterRegistry.counter("foxcash_http_429_total", "group", "authenticated").increment();
            response.setStatus(429); // HTTP 429 Too Many Requests (sem constante na spec Servlet)
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    ErrorResponse.of("too_many_requests",
                            "Limite de requisicoes excedido. Tente novamente mais tarde.")));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Override de rate limit por lojista (Spec 98 — D4.3): principal
     * {@code ROLE_MERCHANT} consulta {@code rate_limit_rps} (janela 1s) e
     * {@code rate_limit_rpm} (janela 60s). Prioridade: rps se definido, senão
     * rpm, senão {@code null} (→ teto global, fail-closed).
     */
    private RateLimitWindow merchantOverride(Authentication authentication, String principal) {
        boolean isMerchant = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_MERCHANT".equals(a.getAuthority()));
        if (!isMerchant) {
            return null;
        }
        UUID merchantId;
        try {
            merchantId = UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            return null; // principal inesperado → teto global
        }
        com.foxcash.domain.port.out.MerchantConfigProvider.RateLimitOverride override =
                merchantConfigProvider.rateLimit(merchantId).orElse(null);
        if (override == null) {
            return null;
        }
        if (override.rps() != null) {
            return new RateLimitWindow(override.rps(), 1);
        }
        if (override.rpm() != null) {
            return new RateLimitWindow(override.rpm(), 60);
        }
        return null;
    }

    /** Janela efetiva do rate limit (override por lojista). */
    private record RateLimitWindow(int maxRequests, int windowSeconds) {
    }
}
