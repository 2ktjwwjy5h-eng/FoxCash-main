package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Inspecao de corpo (Spec 63 — camada app; complementa o WAF do Cloudflare).
 *
 * <p>Detecta payloads de ataque (SQLi/XSS/path traversal) no RAW body de
 * POST/PUT/PATCH e responde <b>403 {@code body_inspection_failed}</b> <b>antes</b>
 * do rate limit e da autenticacao — o ataque nao consome CPU/DB/threads de
 * auth nem gera log de login. Cobre o corpo que o WAF do Cloudflare (plano
 * Free) NAO inspeciona ({@code http.request.body.raw} exige WAF Advanced).
 *
 * <p>Registrado no {@link SecurityConfig} via {@code addFilterBefore} na
 * posicao imediatamente anterior ao {@link RateLimitFilter} (execucao:
 * {@code requestId → bodyInspection → rateLimit → auth}). O body ja chega
 * cacheado pelo {@link RequestBodySizeFilter} (servlet filter, precedencia
 * maxima) — este filtro re-le o cache, inspeciona e re-expoe via
 * {@link CachedBodyHttpServletRequest} para os consumidores a jusante
 * (mesmo padrao do {@link WebhookSignatureFilter}).
 *
 * <p><b>Calibracao (falso positivo):</b> os padroes sao por COMBINACAO de
 * tokens SQL ("union select", "insert into", "select x from", "1=1", ...),
 * nunca palavras soltas — "select"/"union" isolados nao bloqueiam. Ainda
 * assim, qualquer campo de texto livre (descricao de cobranca, nome de loja)
 * pode, em teoria, conter uma combinacao legitima: se ocorrer, desligar por
 * env {@code FOXCASH_BODY_INSPECTION_ENABLED=false} (reversao imediata) e afinar
 * o padrao. Webhooks do PSP excluidos (reentrega nunca sacrificada).
 *
 * <p><b>Alerta (Spec 64):</b> a cada bloqueio o
 * {@link SecurityAlertPublisher#recordBodyInspectionBlocked} conta a rajada
 * (threshold default 5 em 60s) e publica {@code BODY_INSPECTION_BLOCKED} no
 * outbox (payload mascarado: path + padrao + categoria, sem corpo/IP).
 */
@Component
public class RequestBodyInspectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyInspectionFilter.class);

    /** Metodos inspecionados (contrato HTTP com corpo de dados). */
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    /** Teto defensivo de leitura — espelha o {@link RequestBodySizeFilter}; na
     * pratica o body ja chega limitado a 1 MiB por ele. */
    private static final int MAX_SAFE_READ_BYTES = 1_048_576;

    /** Throttle do WARN de bloqueio: 1 log a cada 10s (evita flood de log num
     * ataque automatizado; mesmo padrao do TurnstileVerifier). */
    private static final long BLOCK_LOG_THROTTLE_MS = 10_000L;

    private final BodyInspectionProperties properties;
    private final ObjectMapper objectMapper;
    private final SecurityAlertPublisher securityAlerts;
    private final MeterRegistry meterRegistry;

    private volatile long lastBlockLogAtMillis = 0L;

    public RequestBodyInspectionFilter(BodyInspectionProperties properties,
                                       ObjectMapper objectMapper,
                                       SecurityAlertPublisher securityAlerts,
                                       MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.securityAlerts = securityAlerts;
        this.meterRegistry = meterRegistry;
    }

    /** Padroes de deteccao — ordem importa (primeiro match vence). */
    private static final List<DetectionPattern> PATTERNS = List.of(
            // ---- SQL injection (combinacoes, nao palavras soltas) ----
            new DetectionPattern("sql_injection", "union-select",
                    Pattern.compile("(?i)\\bunion\\s+(all\\s+)?select\\b")),
            new DetectionPattern("sql_injection", "select-from",
                    Pattern.compile("(?i)\\bselect\\b\\s+[^\\s]{1,40}\\s+from\\b")),
            new DetectionPattern("sql_injection", "insert-into",
                    Pattern.compile("(?i)\\binsert\\s+into\\b")),
            new DetectionPattern("sql_injection", "update-set",
                    Pattern.compile("(?i)\\bupdate\\b\\s+\\w+\\s+set\\b[^\\n]{0,40}=\\s*")),
            new DetectionPattern("sql_injection", "delete-from",
                    Pattern.compile("(?i)\\bdelete\\s+from\\b")),
            new DetectionPattern("sql_injection", "drop-table",
                    Pattern.compile("(?i)\\bdrop\\s+table\\b")),
            new DetectionPattern("sql_injection", "alter-table",
                    Pattern.compile("(?i)\\balter\\s+table\\b")),
            new DetectionPattern("sql_injection", "create-table",
                    Pattern.compile("(?i)\\bcreate\\s+table\\b")),
            new DetectionPattern("sql_injection", "time-based",
                    Pattern.compile("(?i)\\b(?:sleep|pg_sleep|benchmark)\\s*\\(")),
            new DetectionPattern("sql_injection", "xp-cmdshell",
                    Pattern.compile("(?i)\\bxp_cmdshell\\b")),
            new DetectionPattern("sql_injection", "information-schema",
                    Pattern.compile("(?i)\\binformation_schema\\b")),
            new DetectionPattern("sql_injection", "concat-call",
                    Pattern.compile("(?i)\\bconcat\\s*\\(")),
            new DetectionPattern("sql_injection", "cast-as",
                    Pattern.compile("(?i)\\bcast\\s*\\(\\s*[^)]{0,30}\\bas\\b")),
            new DetectionPattern("sql_injection", "boolean-tautology",
                    Pattern.compile("(?i)\\b(or|and)\\b\\s+[\\w'\"]{1,40}\\s*(?:=|<|>|like)\\s*[\\w'\"]{1,40}\\s*(?:--|#|/\\*)")),
            new DetectionPattern("sql_injection", "one-equals-one",
                    Pattern.compile("(?i)\\b1\\s*=\\s*1\\b")),
            new DetectionPattern("sql_injection", "char-call",
                    Pattern.compile("(?i)\\bchar\\s*\\(\\s*\\d+\\s*\\)")),
            // ---- XSS ----
            new DetectionPattern("xss", "script-tag",
                    Pattern.compile("(?i)<\\s*script")),
            new DetectionPattern("xss", "javascript-uri",
                    Pattern.compile("(?i)javascript:")),
            new DetectionPattern("xss", "vbscript-uri",
                    Pattern.compile("(?i)vbscript:")),
            new DetectionPattern("xss", "event-handler",
                    Pattern.compile("(?i)\\bon(?:error|load|click|mouseover|focus|blur)\\s*=")),
            new DetectionPattern("xss", "iframe-tag",
                    Pattern.compile("(?i)<\\s*iframe")),
            new DetectionPattern("xss", "img-tag",
                    Pattern.compile("(?i)<\\s*img")),
            new DetectionPattern("xss", "svg-tag",
                    Pattern.compile("(?i)<\\s*svg")),
            new DetectionPattern("xss", "document-cookie",
                    Pattern.compile("(?i)document\\.cookie")),
            new DetectionPattern("xss", "alert-eval-prompt",
                    Pattern.compile("(?i)\\b(?:alert|eval|prompt)\\s*\\(")),
            new DetectionPattern("xss", "css-expression",
                    Pattern.compile("(?i)expression\\s*\\(")),
            new DetectionPattern("xss", "data-html-uri",
                    Pattern.compile("(?i)data:\\s*text/html")),
            new DetectionPattern("xss", "unicode-escaped-lt",
                    Pattern.compile("(?i)\\\\u003c")),
            new DetectionPattern("xss", "html-encoded-script",
                    Pattern.compile("(?i)&lt;\\s*script")),
            // ---- Path traversal ----
            new DetectionPattern("path_traversal", "dotdot-slash",
                    Pattern.compile("(?:\\.\\./){1,}")),
            new DetectionPattern("path_traversal", "dotdot-backslash",
                    Pattern.compile("(?:\\.\\.\\\\){1,}")),
            new DetectionPattern("path_traversal", "encoded-dotdot-slash",
                    Pattern.compile("(?i)%2e%2e%2f")),
            new DetectionPattern("path_traversal", "encoded-dotdot-backslash",
                    Pattern.compile("(?i)%2e%2e%5c")),
            new DetectionPattern("path_traversal", "etc-passwd",
                    Pattern.compile("(?i)/etc/passwd")),
            new DetectionPattern("path_traversal", "windows-dir",
                    Pattern.compile("(?i)c:\\\\windows")),
            new DetectionPattern("path_traversal", "win-ini",
                    Pattern.compile("(?i)win\\.ini")),
            new DetectionPattern("path_traversal", "file-uri",
                    Pattern.compile("(?i)file://"))
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String method = request.getMethod();
        if (method == null || !BODY_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return true;
        }
        // Multipart: body binário arbitrário (upload de mídia — Spec 79/D7) não é
        // alvo da inspeção textual e re-embrulhar quebraria o getParts(). O
        // conteúdo textual dos campos do form é validado na aplicação (Spec 79).
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT)
                .startsWith("multipart/form-data")) {
            return true;
        }
        if (request.getContentLengthLong() == 0) { // sem corpo declarado
            return true;
        }
        // SEC-18 (revisão de segurança 2026-08-11): o path é NORMALIZADO
        // (StringUtils.cleanPath colapsa `..`) para o casamento de exclusão —
        // antes, /webhooks/../webhooks/pix (que o container resolve para
        // /webhooks/pix) NÃO casava com o excluded "/webhooks/" e o filtro
        // inspecionava o payload do PSP (falso positivo de SQLi em reentrega
        // legítima — disponibilidade). Mesmo padrão do SEC-17.
        String path = org.springframework.util.StringUtils.cleanPath(request.getRequestURI());
        for (String excluded : properties.getExcludedPaths()) {
            if (path.startsWith(excluded)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Leitura defensiva com teto (na pratica o RequestBodySizeFilter ja
        // limitou e cacheou o body; readNBytes nunca aloca acima do teto).
        byte[] body = request.getInputStream().readNBytes(MAX_SAFE_READ_BYTES + 1);
        if (body.length > MAX_SAFE_READ_BYTES) {
            // Nao deve acontecer (size filter corre antes) — defensivo, mesmo
            // codigo do RequestBodySizeFilter.
            writeBlocked(response, "payload_too_large");
            return;
        }

        String text = new String(body, StandardCharsets.UTF_8);
        int inspectLen = Math.min(text.length(), properties.getMaxInspectBytes());
        Optional<DetectionPattern> hit = detect(text, inspectLen);
        if (hit.isPresent()) {
            DetectionPattern p = hit.get();
            // Spec 64: rajada de bloqueios por inspecao → alerta no outbox
            // (payload mascarado: path + padrao + categoria — sem corpo/IP).
            securityAlerts.recordBodyInspectionBlocked(request.getRequestURI(), p.patternName(), p.category());
            // Spec 66 (Fase C): metrica observavel no /actuator/prometheus —
            // contador por padrao/categoria (o total e a soma das series). O
            // monitor ops/monitor-body-inspection.sh + esta metrica sao a
            // evidencia para calibrar/reverter o filtro.
            meterRegistry.counter("foxcash.body_inspection.blocks",
                    "pattern", p.patternName(), "category", p.category()).increment();
            // WARN throttled (1/10s) p/ monitoramento operacional — cada bloqueio
            // gera evidencia nos logs (ops/monitor-body-inspection.sh).
            logBlockThrottled(request.getRequestURI(), p);
            writeBlocked(response, "body_inspection_failed");
            return;
        }

        chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /** WARN por bloqueio com throttle (1 log a cada BLOCK_LOG_THROTTLE_MS). */
    private void logBlockThrottled(String path, DetectionPattern p) {
        long now = System.currentTimeMillis();
        if (now - lastBlockLogAtMillis <= BLOCK_LOG_THROTTLE_MS) {
            return;
        }
        lastBlockLogAtMillis = now;
        log.warn("Inspecao de corpo bloqueou request: path={} pattern={} category={}",
                path, p.patternName(), p.category());
    }

    private Optional<DetectionPattern> detect(String text, int inspectLen) {
        for (DetectionPattern p : PATTERNS) {
            if (p.pattern().matcher(text).region(0, inspectLen).find()) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private void writeBlocked(HttpServletResponse response, String code) throws IOException {
        String message = "body_inspection_failed".equals(code)
                ? "Conteudo do corpo bloqueado pela inspecao de seguranca"
                : "Corpo da requisicao acima do limite";
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ErrorResponse.of(code, message)));
    }

    /** Padrao nomeado de deteccao (categoria + nome para o payload do alerta). */
    private record DetectionPattern(String category, String patternName, Pattern pattern) {
    }
}
