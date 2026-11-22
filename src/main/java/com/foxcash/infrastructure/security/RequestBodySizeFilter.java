package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Teto global de tamanho do corpo da requisicao (auditoria 2026-08-05/M-01).
 *
 * <p>Antes nao havia limite de body e o {@link SensitiveDataLoggingFilter}
 * cacheava o corpo inteiro em memoria ({@code ContentCachingRequestWrapper}
 * com limite default = ilimitado) — qualquer cliente autenticado (e a chave de
 * API e gratuita, cadastro publico) podia enviar um corpo de centenas de MB e
 * derrubar o heap da instancia single-VPS (DoS de memoria). Este filtro le no
 * maximo {@value MAX_BODY_BYTES} bytes ({@code readNBytes} — nunca aloca o
 * corpo inteiro) e responde <b>413 {@code payload_too_large}</b> acima disso;
 * o corpo valido e reexposto via {@link CachedBodyHttpServletRequest} para os
 * filtros/controllers a jusante (mesmo padrao do {@link WebhookSignatureFilter}).
 *
 * <p>Rodando com {@link Ordered#HIGHEST_PRECEDENCE} — antes da cadeia de
 * seguranca e dos demais filtros — o body ja chega limitado a qualquer
 * consumidor. Content-Length declarado acima do teto e rejeitado no fast path,
 * sem ler o corpo.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodySizeFilter extends OncePerRequestFilter {

    /** Teto do corpo (1 MiB — mesmo limite do {@link WebhookSignatureFilter}). */
    static final int MAX_BODY_BYTES = 1_048_576;

    /** Metodos que podem carregar corpo no contrato HTTP. */
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ObjectMapper objectMapper;

    public RequestBodySizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (method == null || !BODY_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return true;
        }
        // Multipart: o parse é do container (Spring MultipartResolver) e o body
        // contém binário arbitrário — re-embrulhar via CachedBodyHttpServletRequest
        // quebraria o getParts() (Spec 79/D7: upload de mídia do broadcast).
        // O tamanho é controlado pelo spring.servlet.multipart.* (application.yml).
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT)
                .startsWith("multipart/form-data")) {
            return true;
        }
        return request.getContentLengthLong() == 0; // sem corpo declarado
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Fast path: Content-Length declarado acima do teto — rejeita sem ler o corpo.
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            writeTooLarge(response);
            return;
        }
        // Leitura limitada: nunca aloca mais que MAX_BODY_BYTES + 1 bytes.
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            writeTooLarge(response);
            return;
        }
        chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private void writeTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponse.of("payload_too_large", "Corpo da requisicao acima do limite (1 MiB)")));
    }
}
