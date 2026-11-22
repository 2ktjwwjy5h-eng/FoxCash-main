package com.foxcash.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolve o IP de origem da requisicao para a trilha de auditoria (Spec 37).
 *
 * <p>Auditoria 2026-08-05 (M10): {@code X-Forwarded-For} so e considerado quando
 * {@code foxcash.security.trust-proxy=true} (proxy reverso confiavel que
 * SOBRESCREVE o header). Default {@code false}: usa {@code getRemoteAddr} — o
 * header falsificado pelo cliente nao influencia rate limit nem auditoria.
 */
@Component
public class ClientIpResolver {

    private static final String UNKNOWN = "unknown";

    private final boolean trustProxy;

    public ClientIpResolver(@Value("${foxcash.security.trust-proxy:false}") boolean trustProxy) {
        this.trustProxy = trustProxy;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        if (trustProxy) {
            // Spec 63 (critério 7): com Cloudflare na frente, CF-Connecting-IP é o
            // IP real do cliente (setado/sobrescrito pelo próprio Cloudflare — não
            // forjável via proxy ON). O primeiro XFF, com o edge do Railway no meio,
            // começa com o IP de egress do Cloudflare (varia por request e quebraria
            // rate limit por IP e a trilha de auditoria).
            String cfConnectingIp = request.getHeader("CF-Connecting-IP");
            if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
                String cfIp = cfConnectingIp.trim();
                return cfIp.length() > 45 ? cfIp.substring(0, 45) : cfIp;
            }
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Primeiro endereco = cliente original (os demais sao proxies).
                String first = forwarded.split(",")[0].trim();
                if (!first.isBlank() && !UNKNOWN.equalsIgnoreCase(first)) {
                    return first.length() > 45 ? first.substring(0, 45) : first;
                }
            }
        }
        String remote = request.getRemoteAddr();
        if (remote == null || remote.isBlank()) {
            return UNKNOWN;
        }
        return remote;
    }
}
