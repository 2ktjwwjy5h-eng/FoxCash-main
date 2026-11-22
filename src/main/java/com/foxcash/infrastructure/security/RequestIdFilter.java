package com.foxcash.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Header {@code X-Request-Id} em toda resposta (Spec 18) — rastreabilidade
 * ponta a ponta (suporte/auditoria). Usa o header enviado pelo cliente se
 * presente; caso contrário gera um UUID. Também injeta o id no MDC para os
 * logs da requisição.
 *
 * <p>Auditoria 2026-08-05 (B-03): o header do cliente é validado antes de ser
 * ecoado — apenas caracteres seguros {@code [A-Za-z0-9._:-]} (máx. 128). Valor
 * fora do padrão (injeção de header via CR/LF, tamanho excessivo, caracteres
 * de controle) é substituído por um UUID: a resposta nunca ecoa algo que possa
 * poluir logs ou headers de retorno.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Padrão seguro de request id: caracteres imprimíveis comuns + max 128. */
    private static final Pattern SAFE_REQUEST_ID =
            Pattern.compile("^[A-Za-z0-9._:\\-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank()
                || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
