package com.foxcash.domain.exception;

/**
 * Evento invalido na configuracao de webhook (Spec 35) — 400
 * {@code invalid_event} (nome fora do enum {@code WebhookEventType}).
 */
public class InvalidWebhookEventException extends RuntimeException {

    public InvalidWebhookEventException(String event) {
        super("Evento de webhook inválido: " + event);
    }
}
