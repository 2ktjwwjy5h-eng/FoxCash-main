package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.domain.port.out.RateLimitStore;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate limiting por IP + rota (Spec 18, ADR-009) — janela fixa.
 *
 * <p>Aplicado apenas às rotas públicas de abuso: cadastro de lojista
 * ({@code POST /api/v1/merchants}), logins (master e portal), forgot/reset de
 * senha. Excedeu o limite → **429** {@code too_many_requests} antes de qualquer
 * processamento.
 *
 * <p>O contador vive em um {@link RateLimitStore} selecionável (Spec 43, M-05):
 * {@code InMemoryRateLimitStore} (default — single-instance) ou
 * {@code RedisRateLimitStore} (multi-instância, fail-open se indisponível). O
 * filtro ignora o store concreto — mesmo contrato externo.
 *
 * <p>Auditoria 2026-08-05 (M10): {@code X-Forwarded-For} só é considerado quando
 * {@code foxcash.security.trust-proxy=true} (proxy reverso confiável que
 * SOBRESCREVE o header). Default {@code false}: usa {@code remoteAddr} — um
 * cliente não consegue burlar o limite falsificando o header.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** Rotas públicas sujeitas a limite (método POST). */
    private static final String[] LIMITED_PATHS = {
            "/api/v1/merchants",
            "/api/v1/auth/master/login",
            "/api/v1/merchant-auth/login",
            "/api/v1/merchant-auth/forgot-password", // Spec 40: evita spam de tokens
            "/api/v1/merchant-auth/reset-password",  // Spec 42/B-02: todo endpoint público de auth limitado por IP
            // Spec 69 (critério 12): o step-up é superfície de verificação de
            // código — teto próprio por IP (defesa em profundidade; o
            // verifySecondFactor já tem lockout de 5 falhas, o teto protege a
            // superfície antes do lockout). Também coberto por principal
            // (AuthenticatedRateLimitFilter).
            "/api/v1/master/mfa/step-up",
            // Spec 94 (espelho do critério 12 da Spec 69): o confirm do MFA do
            // lojista é superfície de verificação de código — teto próprio por
            // IP (defesa em profundidade; o verifySecondFactor já tem lockout
            // de 5 falhas). Também coberto por principal.
            "/api/v1/portal/mfa/confirm"
    };

    /** Storefront (Spec 48) — GET limitado por IP (Spec 43): rota consumida por bots, evita abuso. */
    private static final String STOREFRONT_PATH = "/api/v1/foxcash/storefront";

    /** Compra de plano (Spec 49) — POST por prefixo ({id} variável), limitado por IP. */
    private static final String PLAN_PURCHASE_PREFIX = "/api/v1/foxcash/plans/";

    /**
     * Rotas transacionais (Spec 58 — rede de segurança por IP): autenticadas,
     * sem limite por usuário antes da Spec 58; ganham teto por IP maior que o
     * das públicas (baseline 300/min). Todos os métodos (o bot polla
     * GET /deliveries/pending). Não conflita com o AuthenticatedRateLimitFilter
     * (chaves diferentes: IP vs principal).
     */
    private static final String[] TRANSACTIONAL_PREFIXES = {
            "/api/v1/transactions/",
            "/api/v1/billing/",
            "/api/v1/foxcash/deliveries/",
            "/api/v1/portal/dashboard/"
    };

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final boolean trustProxy;
    private final RateLimitStore store;
    private final SecurityAlertPublisher securityAlerts;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper,
                           @Value("${foxcash.security.trust-proxy:false}") boolean trustProxy,
                           RateLimitStore store, SecurityAlertPublisher securityAlerts,
                           io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.trustProxy = trustProxy;
        this.store = store;
        this.securityAlerts = securityAlerts;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        // SEC-16 (revisão de segurança 2026-08-11): o path é NORMALIZADO com
        // StringUtils.cleanPath (colapsa `..`, `.` e // duplicadas) — as decisões
        // de rota (transacional/storefront/plano/limitada) usam o MESMO path que
        // o container/router enxerga. Antes, uma URI com `..` (ex.:
        // /api/v1/transactions/../auth/master/login) era avaliada com o path cru:
        // caía no grupo TRANSACIONAL (teto alto) em vez do bucket do login real
        // (teto baixo) — bypass do rate limit por path traversal.
        String path = org.springframework.util.StringUtils.cleanPath(request.getRequestURI());
        // Rotas transacionais (Spec 58): limitadas por IP (teto maior) quando o
        // grupo transactional está habilitado — qualquer método.
        if (properties.getTransactional().isEnabled() && isTransactionalPath(path)) {
            return false;
        }
        // Storefront (Spec 48): GET também limitado por IP — o bot consome a
        // config da loja; sem limite, uma chave vazada permite abuso sem custo.
        if (STOREFRONT_PATH.equals(path)) {
            return false;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // Compra de plano (Spec 49): POST com {id} no path — prefixo.
        if (path.startsWith(PLAN_PURCHASE_PREFIX)) {
            return false;
        }
        for (String limited : LIMITED_PATHS) {
            if (path.equals(limited)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // SEC-02: a chave usa a rota NORMALIZADA (prefixo fixo) — cada {id} no
        // path não pode criar bucket novo (bypass do teto por cardinalidade).
        // SEC-16: o path também passa por StringUtils.cleanPath (colapsa `..`,
        // `.` e //) — uma URI com `..` não pode cair num bucket de rota errada
        // (ex.: /api/v1/transactions/../auth/master/login no bucket transacional
        // de teto alto em vez do bucket de login). Mesma normalização do
        // shouldNotFilter: a chave e a decisão de teto usam o path que o
        // container/router enxerga.
        String path = org.springframework.util.StringUtils.cleanPath(request.getRequestURI());
        String key = RateLimitKey.normalize(path) + "|" + clientIp(request);
        // Rota transacional → teto próprio (Spec 58, baseline 300/min); senão o
        // teto padrão das rotas públicas de abuso (20/min).
        boolean transactional = isTransactionalPath(path);
        RateLimitProperties.Transactional transactionalConfig = properties.getTransactional();
        int maxRequests = transactional ? transactionalConfig.getMaxRequests() : properties.getMaxRequests();
        long windowSeconds = transactional ? transactionalConfig.getWindowSeconds() : properties.getWindowSeconds();

        if (!store.tryAcquire(key, maxRequests, windowSeconds)) {
            // Spec 64: rajada de 429 num grupo/rota → alerta de segurança (throttled).
            securityAlerts.recordRateLimitBurst(group(path));
            // Spec 126: métrica de 429 por grupo (alerta proativo de abuso).
            meterRegistry.counter("foxcash_http_429_total", "group", group(path)).increment();
            response.setStatus(429); // HTTP 429 Too Many Requests (sem constante na spec Servlet)
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    ErrorResponse.of("too_many_requests",
                            "Limite de requisicoes excedido. Tente novamente mais tarde.")));
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isTransactionalPath(String path) {
        for (String prefix : TRANSACTIONAL_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Grupo/rota para o payload do alerta (Spec 64) — sem IP (privacidade). */
    private String group(String path) {
        if (isTransactionalPath(path)) {
            return "transactional";
        }
        return path;
    }

    private String clientIp(HttpServletRequest request) {
        if (trustProxy) {
            // Spec 63 (critério 7): CF-Connecting-IP = IP real do cliente (setado
            // pelo Cloudflare); o primeiro XFF com o edge do Railway no meio é o
            // egress do Cloudflare (variável por request → quebraria o rate limit).
            String cfConnectingIp = request.getHeader("CF-Connecting-IP");
            if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
                return cfConnectingIp.trim();
            }
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
