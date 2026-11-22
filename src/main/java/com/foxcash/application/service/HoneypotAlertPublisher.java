package com.foxcash.application.service;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.notification.WebhookEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Alerta de honeypot (Spec 98 — D4.5): cada {@code createCharge} de lojista em
 * ambiente controlado publica {@code HONEYPOT_TRIGGERED} no outbox (Spec 35)
 * com **throttle por janela por lojista** (padrão Spec 64 — no máximo 1 evento
 * a cada {@code THROTTLE_SECONDS}) e grava a trilha {@code platform_activity_log}
 * (Spec 41, actor SYSTEM — sem IP, fora de request).
 *
 * <p><b>Fail-safe:</b> a publicação roda em transação PRÓPRIA
 * ({@code REQUIRES_NEW}) e qualquer exceção é capturada + WARN — o caminho da
 * cobrança trap NUNCA é derrubado por falha de alerta (o atacante não pode ser
 * alertado, mas o operador também não pode derrubar a ilusão).
 */
@Service
public class HoneypotAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(HoneypotAlertPublisher.class);

    /** Throttle default por lojista (1 alerta a cada 60s). */
    private static final long THROTTLE_SECONDS = 60;

    private final WebhookEventPublisher eventPublisher;
    private final PlatformActivityRecorder activityRecorder;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    /** Ultimo alerta por lojista (throttle por janela — estado por-instância). */
    private final ConcurrentHashMap<UUID, AtomicReference<Instant>> lastAlertAt = new ConcurrentHashMap<>();

    public HoneypotAlertPublisher(WebhookEventPublisher eventPublisher,
                                  PlatformActivityRecorder activityRecorder,
                                  PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.eventPublisher = eventPublisher;
        this.activityRecorder = activityRecorder;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * Registra a tentativa de cobrança em lojista honeypot (throttled). Chama o
     * operador SEM revelar nada ao atacante (a conta segue operando no trap).
     */
    public void record(UUID merchantId, UUID transactionId) {
        Instant now = clock.instant();
        AtomicReference<Instant> last = lastAlertAt.computeIfAbsent(merchantId,
                id -> new AtomicReference<>(Instant.EPOCH));
        Instant prev = last.get();
        if (prev.plusSeconds(THROTTLE_SECONDS).isAfter(now)) {
            return; // já alertado nesta janela — sem spam ao operador
        }
        if (!last.compareAndSet(prev, now)) {
            return; // outro request publicou primeiro
        }
        publish(merchantId, transactionId, now);
    }

    private void publish(UUID merchantId, UUID transactionId, Instant now) {
        try {
            txTemplate.executeWithoutResult(status -> {
                eventPublisher.publish(merchantId, WebhookEventType.HONEYPOT_TRIGGERED,
                        Map.of("merchantId", merchantId, "transactionId", transactionId));
                activityRecorder.record(PlatformActorType.SYSTEM, null,
                        PlatformAction.HONEYPOT_TRIGGERED, "TRANSACTION", transactionId,
                        merchantId, Map.of("merchantId", merchantId));
            });
            log.warn("HONEYPOT_TRIGGERED: cobranca trap para merchant={} transacao={} (operador notificado)",
                    merchantId, transactionId);
        } catch (Exception e) {
            // Fail-safe: o alerta nunca derruba o caminho da cobrança trap.
            log.warn("Falha ao publicar HONEYPOT_TRIGGERED (merchant={}): {}", merchantId, e.getMessage());
        }
    }
}
