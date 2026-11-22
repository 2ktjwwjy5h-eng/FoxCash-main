package com.foxcash.application.service;

import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.infrastructure.config.FingerprintProperties;
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
 * Alerta de device fingerprint em múltiplas contas (Spec 141) — quando o MESMO
 * hash é visto no cadastro/login de {@code alert-threshold} lojistas distintos
 * ({@code distinct_merchants}), publica {@code FINGERPRINT_DEVICE_SHARED} no
 * outbox (Spec 35) com **throttle por hash** (padrão Spec 64/HONEYPOT — no
 * máximo 1 alerta a cada {@code alert-throttle-seconds} por hash).
 *
 * <p><b>Fail-safe:</b> a publicação roda em transação PRÓPRIA
 * ({@code REQUIRES_NEW}) e qualquer exceção é capturada + WARN — o caminho do
 * cadastro/login NUNCA é derrubado por falha de alerta (mesmo padrão do
 * {@link HoneypotAlertPublisher}).
 *
 * <p>O payload é mascarado: {@code fingerprintHash} (identificador pseudônimo)
 * + {@code distinctMerchants} (contagem) — nunca email/documento/dados reais.
 */
@Service
public class FingerprintAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(FingerprintAlertPublisher.class);

    private final WebhookEventPublisher eventPublisher;
    private final FingerprintProperties properties;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    /** Último alerta por hash (throttle — estado por-instância, padrão HONEYPOT). */
    private final ConcurrentHashMap<String, AtomicReference<Instant>> lastAlertAt = new ConcurrentHashMap<>();

    public FingerprintAlertPublisher(WebhookEventPublisher eventPublisher,
                                     FingerprintProperties properties,
                                     PlatformTransactionManager transactionManager,
                                     Clock clock) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * Registra um fingerprint visto em {@code distinctMerchants} contas. Publica
     * o alerta (throttled por hash) quando o limiar é atingido e o fingerprint
     * está habilitado. Best-effort — nunca lança para o chamador.
     */
    public void maybeAlert(String fingerprintHash, int distinctMerchants) {
        if (!properties.isEnabled()) {
            return;
        }
        int threshold = properties.getAlertThreshold();
        if (threshold <= 0 || distinctMerchants < threshold) {
            return;
        }
        Instant now = clock.instant();
        AtomicReference<Instant> last = lastAlertAt.computeIfAbsent(fingerprintHash,
                h -> new AtomicReference<>(Instant.EPOCH));
        Instant prev = last.get();
        if (prev.plusSeconds(properties.getAlertThrottleSeconds()).isAfter(now)) {
            return; // já alertado nesta janela — sem spam ao operador
        }
        if (!last.compareAndSet(prev, now)) {
            return; // outro request publicou primeiro
        }
        publish(fingerprintHash, distinctMerchants);
    }

    private void publish(String fingerprintHash, int distinctMerchants) {
        try {
            txTemplate.executeWithoutResult(status -> eventPublisher.publish(null,
                    WebhookEventType.FINGERPRINT_DEVICE_SHARED,
                    Map.of("fingerprintHash", fingerprintHash,
                            "distinctMerchants", distinctMerchants)));
            log.warn("FINGERPRINT_DEVICE_SHARED: mesmo dispositivo em {} contas (hash={}) — operador notificado",
                    distinctMerchants, fingerprintHash);
        } catch (Exception e) {
            // Fail-safe: o alerta nunca derruba o caminho do cadastro/login.
            log.warn("Falha ao publicar FINGERPRINT_DEVICE_SHARED (hash={}): {}", fingerprintHash,
                    e.getMessage());
        }
    }
}
