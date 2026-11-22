package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Webhook de saida nao encontrado para o lojista (Spec 35) — 404
 * {@code webhook_not_found} sem revelar existencia de webhook de outro lojista.
 */
public class WebhookNotFoundException extends RuntimeException {

    public WebhookNotFoundException(UUID id) {
        super("Webhook não encontrado: " + id);
    }
}
