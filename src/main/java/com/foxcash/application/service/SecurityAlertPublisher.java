package com.foxcash.application.service;

import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.AlertCounterStore;
import com.foxcash.infrastructure.config.SecurityAlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publica alertas de SEGURANCA no outbox (Spec 64) — eventos de plataforma
 * (sem merchant) entregues ao webhook de operador (Spec 53/54), com fallback
 * log quando nao configurado.
 *
 * <p>Mesmo pipeline do {@code JOB_FAILED}/{@code OUTBOX_DEAD}, mas com
 * **contagem de rajada** e **throttle anti-spam**: os pontos de disparo
 * ({@code RateLimitFilter}, {@code WebhookSignatureFilter},
 * {@code RedisRateLimitStore}, services de login) chamam
 * {@code record...()} — o publisher conta os eventos na janela (por instancia)
 * e publica **1 evento por tipo por janela de throttle** quando o limiar e
 * atingido. Repeticao da rajada dentro da janela nao gera outro evento.
 *
 * <p><b>Fail-safe:</b> sem webhook de operador configurado → fallback log (o
 * {@code HttpMerchantNotifier} loga evento de plataforma sem destino). A
 * publicacao roda em transacao PRÓPRIA ({@code REQUIRES_NEW}, padrao Spec
 * 28/42) — nunca afeta a transacao da venda/login e nunca derruba o caminho
 * critico (excecao capturada + WARN). O payload e mascarado (sem
 * senha/token/segredo/chave/email/documento).
 */
@Service
public class SecurityAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(SecurityAlertPublisher.class);

    private final WebhookEventPublisher eventPublisher;
    private final SecurityAlertProperties properties;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    /**
     * Contador de rajada por (tipo, contexto) — Spec 66 (Fase B): a porta é
     * implementada por {@code InMemoryAlertCounterStore} (default, por-instância)
     * ou {@code RedisAlertCounterStore} (agregado entre réplicas, fail-open).
     */
    private final AlertCounterStore alertCounterStore;

    /** Ultima publicacao por tipo (throttle). */
    private final ConcurrentHashMap<WebhookEventType, AtomicReference<Instant>> lastAlertAt = new ConcurrentHashMap<>();

    public SecurityAlertPublisher(WebhookEventPublisher eventPublisher,
                                  SecurityAlertProperties properties,
                                  PlatformTransactionManager transactionManager,
                                  Clock clock,
                                  AlertCounterStore alertCounterStore) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.alertCounterStore = alertCounterStore;
    }

    /** Rajada de falhas de login (LOGIN_FAILURE_BURST) — source: portal/master. */
    public void recordLoginFailure(String source) {
        SecurityAlertProperties.LoginFailure cfg = properties.getLoginFailure();
        record(WebhookEventType.LOGIN_FAILURE_BURST, "login:" + source,
                cfg.getThreshold(), cfg.getWindowSeconds(),
                Map.of("source", source));
    }

    /** Rajada de 429 num grupo/rota (RATE_LIMIT_BURST). */
    public void recordRateLimitBurst(String group) {
        SecurityAlertProperties.RateLimit cfg = properties.getRateLimit();
        record(WebhookEventType.RATE_LIMIT_BURST, "rate:" + group,
                cfg.getThreshold(), cfg.getWindowSeconds(),
                Map.of("group", group));
    }

    /** Rajada de assinatura invalida (SIGNATURE_FAILURE_BURST) — path do webhook. */
    public void recordSignatureFailure(String path) {
        SecurityAlertProperties.SignatureFailure cfg = properties.getSignatureFailure();
        record(WebhookEventType.SIGNATURE_FAILURE_BURST, "signature:" + path,
                cfg.getThreshold(), cfg.getWindowSeconds(),
                Map.of("path", path));
    }

    /** Rajada de bloqueios por inspecao de corpo (BODY_INSPECTION_BLOCKED) —
     * payload mascarado: path + padrao + categoria (sem corpo/IP/email). */
    public void recordBodyInspectionBlocked(String path, String pattern, String category) {
        SecurityAlertProperties.BodyInspection cfg = properties.getBodyInspection();
        record(WebhookEventType.BODY_INSPECTION_BLOCKED, "body:" + path,
                cfg.getThreshold(), cfg.getWindowSeconds(),
                Map.of("path", path, "pattern", pattern, "category", category));
    }

    /** Fail-open do rate limit Redis (RATE_LIMIT_FAIL_OPEN) — 1 evento ao ativar. */
    public void recordRateLimitFailOpen(String reason) {
        if (!properties.isEnabled()) {
            return;
        }
        publishThrottled(WebhookEventType.RATE_LIMIT_FAIL_OPEN, Map.of("reason", reason));
    }

    /**
     * Conta o evento na janela (por tipo+contexto) e, ao atingir o limiar,
     * publica (throttled). A contagem vive no {@link AlertCounterStore} — em
     * memória (por-instância) ou Redis (agregado, limiar global — Spec 66 B).
     */
    private void record(WebhookEventType type, String counterKey,
                        int threshold, long windowSeconds, Map<String, Object> data) {
        if (!properties.isEnabled() || threshold <= 0) {
            return;
        }
        long fired = alertCounterStore.incrementAndFire(counterKey, threshold, windowSeconds);
        if (fired == 1) {
            publishThrottled(type, data);
        }
    }

    /**
     * Publica o evento no outbox com throttle por tipo (1 por
     * {@code throttle-seconds}) — transacao própria REQUIRES_NEW.
     */
    private void publishThrottled(WebhookEventType type, Map<String, Object> data) {
        Instant now = clock.instant();
        AtomicReference<Instant> last = lastAlertAt.computeIfAbsent(type,
                t -> new AtomicReference<>(Instant.EPOCH));
        Instant prev = last.get();
        if (prev.plusSeconds(properties.getThrottleSeconds()).isAfter(now)) {
            log.debug("Alerta {} throttled (ja publicado ha {}s)", type,
                    java.time.Duration.between(prev, now).toSeconds());
            return;
        }
        if (!last.compareAndSet(prev, now)) {
            return; // outro request publicou primeiro
        }
        publish(type, data);
    }

    private void publish(WebhookEventType type, Map<String, Object> data) {
        try {
            // REQUIRES_NEW: o alerta nunca participa da transacao do chamador
            // (venda/login) — falha do chamador nao apaga o alerta nem vice-versa.
            txTemplate.executeWithoutResult(status ->
                    eventPublisher.publish(null, type, data));
            log.warn("ALERTA DE SEGURANCA [{}] publicado: {}", type, data);
        } catch (Exception e) {
            // Fail-safe: o alerta nunca derruba/degrada o caminho da venda.
            log.warn("Falha ao publicar alerta de seguranca [{}]: {}", type, e.getMessage());
        }
    }
}
