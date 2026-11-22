package com.foxcash.application.service;

import com.foxcash.domain.model.notification.WebhookEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * Alerta operacional de circuito aberto de adquirente (Spec 91 — D5): quando o
 * {@code PixProviderHealthRegistry} abre o circuito de um provedor de cobrança
 * (janela de falhas/timeouts atingida), publica {@code PROVIDER_CIRCUIT_OPEN} no
 * outbox em transação PRÓPRIA ({@code REQUIRES_NEW}) com **fail-safe** — falha de
 * publicação é capturada (WARN) e nunca derruba o fluxo de cobrança. Payload
 * mascarado (provider/motivo — nunca chave).
 */
@Service
public class CircuitOpenAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(CircuitOpenAlertPublisher.class);

    private final WebhookEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;

    public CircuitOpenAlertPublisher(WebhookEventPublisher eventPublisher,
                                     PlatformTransactionManager transactionManager) {
        this.eventPublisher = eventPublisher;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Circuito aberto do provedor (janela de falhas/timeouts atingida — D5). */
    public void publishCircuitOpened(String provider, String reason) {
        try {
            txTemplate.executeWithoutResult(status -> eventPublisher.publish(null,
                    WebhookEventType.PROVIDER_CIRCUIT_OPEN,
                    Map.of("provider", provider, "reason", reason)));
            log.warn("ALERTA OPERACIONAL [PROVIDER_CIRCUIT_OPEN] publicado: provider={}, reason={}",
                    provider, reason);
        } catch (Exception e) {
            // Fail-safe: o alerta nunca derruba/degrada o fluxo de cobrança.
            log.warn("Falha ao publicar alerta PROVIDER_CIRCUIT_OPEN (provider={}): {}",
                    provider, e.getMessage());
        }
    }
}
