package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitiza dados sensiveis (CPF/CNPJ) nos payloads de request/response antes
 * de loga-los (level DEBUG). Nunca desloga o conteudo em texto puro.
 *
 * <p>Auditoria 2026-08-05 (B-A4): a mascara via regex quebrava quando o valor
 * sensivel continha aspas ({@code ([^"]*)} — uma senha com {@code "} vazaria o
 * restante). Agora o JSON e parseado com Jackson e as chaves sensiveis sao
 * mascaradas recursivamente (mesmo criterio do {@code PlatformActivityRecorder});
 * payloads nao-JSON caem no fallback regex (CPF/CNPJ + campos sensiveis).
 */
@Component
public class SensitiveDataLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataLoggingFilter.class);

    // CPF 000.000.000-00 | CNPJ 00.000.000/0000-00 (com ou sem pontuacao)
    static final Pattern CPF_CNPJ = Pattern.compile(
            "\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b|\\b\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2}\\b");

    /** Chaves sensiveis (JSON) que nunca aparecem em claro nos logs (case-insensitive). */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "api_key", "password", "portalpassword", "portal_password",
            "temporarypassword", "temporary_password", "token", "resettoken", "reset_token",
            "secret", "authorization", "signature", "document", "documenthash",
            "pixkey", "pix_key", "destinationkey", "key", "account", "agency", "email",
            // Spec 49: payload das ações de entrega (podem conter links privados)
            // e o destinatário da plataforma nunca aparecem em claro nos logs.
            "payload", "platformuserid", "platform_user_id", "roleid", "groupid",
            "fileurl", "filename", "text");

    /** Fallback regex para payloads nao-JSON (B-A4) — mascara campos entre aspas. */
    static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(\\\"(?:apiKey|api_key|password|portalPassword|portal_password|temporaryPassword|"
                    + "temporary_password|token|secret|authorization|signature|document|pixKey|pix_key|"
                    + "destinationKey|email|resetToken|reset_token)\\s*\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")",
            Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Limite do cache do corpo para log (auditoria 2026-08-05/M-01): o default
     * do {@code ContentCachingRequestWrapper} cacheia o corpo INTEIRO em memoria
     * (ilimitado). Com 64 KiB o log DEBUG mostra apenas um preview — a alocacao
     * por request fica limitada mesmo se alguma rota futura burlar o
     * {@link RequestBodySizeFilter}.
     */
    private static final int REQUEST_CACHE_LIMIT = 64 * 1024; // 64 KiB

    private final boolean enabled;

    public SensitiveDataLoggingFilter(@Value("${foxcash.logging.sanitized:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // SEC-09 (revisão de segurança 2026-08-11): o wrapper só é usado com
        // DEBUG ativo. Antes, o ContentCachingResponseWrapper (sem limite)
        // bufferizava a resposta INTEIRA em memória em TODOS os requests (mesmo
        // sem DEBUG) — amplificação de memória sob carga. Sem DEBUG, a resposta
        // flui normalmente (stream direto, sem buffer de 2x).
        if (!enabled || !log.isDebugEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, REQUEST_CACHE_LIMIT);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        chain.doFilter(requestWrapper, responseWrapper);

        log.debug("HTTP {} {} -> {}", request.getMethod(), request.getRequestURI(), responseWrapper.getStatus());

        String requestBody = readBody(requestWrapper.getContentAsByteArray());
        if (!requestBody.isBlank()) {
            log.debug("Request body (sanitizado): {}", sanitize(requestBody));
        }
        String responseBody = readBody(responseWrapper.getContentAsByteArray());
        if (!responseBody.isBlank()) {
            log.debug("Response body (sanitizado): {}", sanitize(responseBody));
        }

        // Escreve o corpo de volta na resposta (o wrapper consome o stream)
        responseWrapper.copyBodyToResponse();
    }

    private static String readBody(byte[] content) {
        return content == null ? "" : new String(content, StandardCharsets.UTF_8);
    }

    /**
     * Mascara chaves sensiveis em JSON (recursivamente, mesmo aninhadas). Em
     * payload nao-JSON, fallback para a regex de campos sensiveis + CPF/CNPJ.
     */
    static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        try {
            Object tree = MAPPER.readValue(input, Object.class);
            return MAPPER.writeValueAsString(mask(tree));
        } catch (JsonProcessingException e) {
            String masked = SENSITIVE_FIELD.matcher(input).replaceAll("$1***$3");
            return CPF_CNPJ.matcher(masked).replaceAll("***");
        }
    }

    private static Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object v = entry.getValue();
                result.put(key, SENSITIVE_KEYS.contains(key.toLowerCase()) ? "***" : mask(v));
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(SensitiveDataLoggingFilter::mask).toList();
        }
        return value;
    }
}
