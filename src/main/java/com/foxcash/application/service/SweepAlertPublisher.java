package com.foxcash.application.service;

import com.foxcash.domain.model.notification.WebhookEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Alertas operacionais do sweep do saldo do PSP (Spec 92) — eventos de
 * PLATAFORMA (sem merchant) entregues ao webhook de operador (Spec 53/64):
 * {@code SWEEP_FAILED} (excedente não transferido — saldo em risco de freeze) e
 * {@code SWEEP_EXPOSURE_HIGH} (exposição = available − buffer acima do limiar).
 *
 * <p>Mesmo pipeline do {@code JOB_FAILED}/{@code OUTBOX_DEAD}: publica no outbox
 * em transação PRÓPRIA ({@code REQUIRES_NEW}) com **fail-safe** — falha de
 * publicação é capturada (WARN) e nunca derruba o job. Payload mascarado
 * (provedor/motivo/valor — nunca chave/destino completo).
 */
@Service
public class SweepAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(SweepAlertPublisher.class);

    private final WebhookEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;

    public SweepAlertPublisher(WebhookEventPublisher eventPublisher,
                               PlatformTransactionManager transactionManager) {
        this.eventPublisher = eventPublisher;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Excedente NÃO transferido (falha/erro no sweep). */
    public void publishSweepFailed(String provider, String reason) {
        publish(WebhookEventType.SWEEP_FAILED,
                Map.of("provider", provider, "reason", reason));
    }

    /** Exposição do caixa (available − buffer) acima do limiar. */
    public void publishExposureHigh(BigDecimal exposure, BigDecimal threshold) {
        publish(WebhookEventType.SWEEP_EXPOSURE_HIGH,
                Map.of("exposure", exposure.toPlainString(), "threshold", threshold.toPlainString()));
    }

    private void publish(WebhookEventType type, Map<String, Object> data) {
        try {
            txTemplate.executeWithoutResult(status -> eventPublisher.publish(null, type, data));
            log.warn("ALERTA OPERACIONAL [{}] publicado: {}", type, data);
        } catch (Exception e) {
            // Fail-safe: o alerta nunca derruba/degrada o job de sweep.
            log.warn("Falha ao publicar alerta operacional [{}]: {}", type, e.getMessage());
        }
    }
}
