package com.foxcash.infrastructure.notification;

/**
 * Falha PERMANENTE de entrega de webhook de saida (Spec 148) — 4xx do receptor
 * (erro do cliente: URL/config errada, payload rejeitado, etc.). Retentar não
 * resolve: o {@code NotificationProcessor} encerra as tentativas (marca
 * {@code FAILED} direto, sem backoff) em vez de agendar retry.
 */
public class PermanentWebhookDeliveryException extends RuntimeException {

    public PermanentWebhookDeliveryException(String message) {
        super(message);
    }
}
