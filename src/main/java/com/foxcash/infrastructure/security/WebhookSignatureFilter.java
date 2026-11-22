package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.domain.port.out.RateLimitStore;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Valida a assinatura HMAC-SHA256 do payload recebido nos webhooks do PSP:
 * {@code POST /webhooks/pix} (Spec 10/11) e {@code POST /webhooks/payouts}
 * (Spec 36), cada um com o proprio segredo
 * ({@code foxcash.psp.webhook.*} e {@code foxcash.psp.payout-webhook.*}).
 *
 * <p>Sem essa verificacao, qualquer requester poderia enviar {@code PAID} para
 * uma {@code externalReference} e aprovar uma transacao nao paga. A assinatura
 * e computada sobre o <b>raw body</b> com o segredo da rota e comparada (em
 * tempo constante) com o header configurado ({@code foxcash.psp.*.header-name}).
 *
 * <p>Desligavel em dev/tests via {@code foxcash.psp.*.verify-signature=false}
 * (default {@code true}; em producao e sempre obrigatoria — o profile {@code prod}
 * nao define default para os segredos).
 *
 * <p>Spec 59 (2026-08-08): rate limit por IP nos webhooks (teto generoso) no
 * TOPO do {@code doFilterInternal} — flood do mesmo IP leva **429** antes de ler
 * o body/computar HMAC (nao gasta CPU no flood); a reentrega legitima do PSP
 * (minutos) fica muito abaixo do teto e segue 204. O WARN de assinatura invalida
 * e throttled (1 log a cada {@code foxcash.psp.webhook.log-throttle-seconds}).
 */
@Component
public class WebhookSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureFilter.class);

    private static final String PIX_WEBHOOK_PATH = "/webhooks/pix";
    private static final String PAYOUT_WEBHOOK_PATH = "/webhooks/payouts";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Teto do payload do webhook (auditoria 2026-08-05/B4): acima disso → 413. */
    private static final int MAX_PAYLOAD_BYTES = 1_048_576; // 1 MiB

    private final PspProperties pspProperties;
    private final RateLimitProperties rateLimitProperties;
    private final RateLimitStore rateLimitStore;
    private final ObjectMapper objectMapper;
    private final boolean trustProxy;
    private final Clock clock;
    private final SecurityAlertPublisher securityAlerts;

    /** Timestamp do último WARN de assinatura inválida (throttle de log — Spec 59). */
    private final AtomicReference<Instant> lastInvalidSignatureLog = new AtomicReference<>(Instant.EPOCH);

    public WebhookSignatureFilter(PspProperties pspProperties,
                                  RateLimitProperties rateLimitProperties,
                                  RateLimitStore rateLimitStore,
                                  ObjectMapper objectMapper,
                                  @Value("${foxcash.security.trust-proxy:false}") boolean trustProxy,
                                  Clock clock,
                                  SecurityAlertPublisher securityAlerts) {
        this.pspProperties = pspProperties;
        this.rateLimitProperties = rateLimitProperties;
        this.rateLimitStore = rateLimitStore;
        this.objectMapper = objectMapper;
        this.trustProxy = trustProxy;
        this.clock = clock;
        this.securityAlerts = securityAlerts;
    }

    /** Configuracao de assinatura da rota (segredo/header/verificacao). */
    private PspProperties.Webhook configFor(String uri) {
        if (PAYOUT_WEBHOOK_PATH.equals(uri)) {
            return pspProperties.getPayoutWebhook();
        }
        return pspProperties.getWebhook();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // SEC-17 (revisão de segurança 2026-08-11): o path é NORMALIZADO com
        // StringUtils.cleanPath (colapsa `..`, `.` e // duplicadas) — a decisão
        // usa o MESMO path que o container/router enxerga. Antes, uma URI com `..`
        // (ex.: /webhooks/../webhooks/pix) era avaliada com o path cru: NÃO casava
        // com /webhooks/pix → o filtro pulava → o webhook (permitAll no Spring
        // Security) era processado SEM verificação de assinatura — crédito de PAID
        // falso sem posse do segredo do PSP. Mesmo padrão do SEC-05/16.
        String uri = org.springframework.util.StringUtils.cleanPath(request.getRequestURI());
        // Fora das rotas de webhook ou verificacao desligada -> nao filtra.
        if (!PIX_WEBHOOK_PATH.equals(uri) && !PAYOUT_WEBHOOK_PATH.equals(uri)) {
            return true;
        }
        return !configFor(uri).isVerifySignature();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // SEC-17: path normalizado usado em TODAS as decisões (rate limit key,
        // config da rota, logs) — consistência com o shouldNotFilter.
        String path = org.springframework.util.StringUtils.cleanPath(request.getRequestURI());
        // Spec 59: rate limit por IP nos webhooks ANTES de ler o body/computar HMAC.
        // Flood do mesmo IP → 429 too_many_requests (mesmo contrato do RateLimitFilter)
        // sem gastar CPU/DB/threads; reentrega legítima do PSP (minutos) não é afetada.
        RateLimitProperties.Webhook rl = rateLimitProperties.getWebhook();
        if (rateLimitProperties.isEnabled() && rl.isEnabled()) {
            String key = path + "|" + clientIp(request);
            if (!rateLimitStore.tryAcquire(key, rl.getMaxRequests(), rl.getWindowSeconds())) {
                // Spec 64: rajada de 429 nos webhooks → alerta de segurança (throttled).
                securityAlerts.recordRateLimitBurst("webhook");
                response.setStatus(429); // HTTP 429 Too Many Requests (sem constante na spec Servlet)
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(objectMapper.writeValueAsString(
                        ErrorResponse.of("too_many_requests",
                                "Limite de requisicoes excedido. Tente novamente mais tarde.")));
                return;
            }
        }

        PspProperties.Webhook config = configFor(path);
        // Auditoria 2026-08-05 (B4): leitura LIMITADA (readNBytes) — payload acima
        // do teto e rejeitado com 413 sem alocar o corpo inteiro em memoria.
        byte[] rawBody = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1);
        if (rawBody.length > MAX_PAYLOAD_BYTES) {
            log.warn("Webhook rejeitado: payload acima do limite (path={}, bytes={})",
                    path, rawBody.length);
            response.sendError(413, "Payload de webhook acima do limite"); // 413 sem constante na spec Servlet
            return;
        }
        if (!validSignature(path, request, rawBody, config)) {
            // Spec 64: rajada de assinaturas inválidas → alerta de segurança (throttled).
            securityAlerts.recordSignatureFailure(path);
            warnInvalidSignatureThrottled(config, path);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Assinatura de webhook invalida");
            return;
        }

        chain.doFilter(new CachedBodyHttpServletRequest(request, rawBody), response);
    }

    /**
     * Valida a assinatura do webhook. Para {@code /webhooks/pix} (Spec 91 —
     * webhook por adquirente), tenta CADA adquirente configurado em ordem
     * constante: Pagar.me (HMAC-SHA256 com {@code foxcash.psp.webhook.secret},
     * header {@code X-Psp-Signature}) ou Asaas (token no header
     * {@code access_token}/{@code x-api-key} comparado em tempo constante com
     * {@code foxcash.psp.asaas.secret-key}). Sem segredo configurado para o
     * adquirente em prod → fail-closed (nenhum valida → 401). Payouts continuam
     * com o segredo único ({@code payout-webhook.*}).
     */
    private boolean validSignature(String path, HttpServletRequest request, byte[] rawBody,
                                   PspProperties.Webhook config) {
        // Desligável em dev/tests (verify-signature=false).
        if (!config.isVerifySignature()) {
            return true;
        }
        if (PIX_WEBHOOK_PATH.equals(path)) {
            // 1. Pagar.me: HMAC-SHA256 do raw body com o segredo do webhook (atual).
            String expected = computeSignature(config.getSecret(), rawBody);
            String received = request.getHeader(config.getHeaderName());
            if (received != null && MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
            // 2. Asaas: token no header comparado em tempo constante com a
            //    secret-key (D6 — o Asaas não usa HMAC).
            String token = request.getHeader("access_token");
            if (token == null) {
                token = request.getHeader("x-api-key");
            }
            String asaasSecret = pspProperties.getAsaas().getSecretKey();
            if (token != null && asaasSecret != null && !asaasSecret.isBlank()
                    && !asaasSecret.contains("${")
                    && MessageDigest.isEqual(asaasSecret.getBytes(StandardCharsets.UTF_8),
                            token.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
            return false; // fail-closed: nenhum adquirente validou
        }
        // Payouts (Spec 36): segredo único da rota.
        String expected = computeSignature(config.getSecret(), rawBody);
        String received = request.getHeader(config.getHeaderName());
        return received != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Spec 59: WARN de assinatura invalida com throttle — no maximo 1 log a cada
     * {@code log-throttle-seconds} (default 10s), mesmo em flood de 401.
     */
    private void warnInvalidSignatureThrottled(PspProperties.Webhook config, String path) {
        long throttleSeconds = Math.max(0, config.getLogThrottleSeconds());
        Instant now = clock.instant();
        Instant last = lastInvalidSignatureLog.get();
        if (throttleSeconds == 0 || (last.plusSeconds(throttleSeconds).isBefore(now)
                && lastInvalidSignatureLog.compareAndSet(last, now))) {
            log.warn("Webhook rejeitado: assinatura ausente ou invalida (header={}, path={})",
                    config.getHeaderName(), path);
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (trustProxy) {
            // Spec 63 (critério 7): CF-Connecting-IP = IP real do cliente (setado
            // pelo Cloudflare); o primeiro XFF com o edge do Railway no meio é o
            // egress do Cloudflare (variável por request → quebraria o rate limit
            // do webhook por IP).
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

    static String computeSignature(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Nao foi possivel computar HMAC-SHA256", e);
        }
    }
}
