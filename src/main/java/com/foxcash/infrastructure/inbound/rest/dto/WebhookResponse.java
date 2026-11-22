package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.notification.MerchantWebhook;
import com.foxcash.domain.model.notification.WebhookEventType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Resposta de webhook de saida (Spec 35): segredo NUNCA exposto — apenas
 * {@code hasSecret} indica que existe um segredo configurado.
 *
 * <p>Spec 119 (aditivo): {@code status} (ACTIVE/INACTIVE), {@code attempts} e
 * {@code lastError} (última falha de entrega do lojista — do outbox Spec 35).
 * Spec 148: {@code signatureVersion} (v2 default | v1 legado).
 */
public record WebhookResponse(UUID id, String url, boolean hasSecret, boolean active,
                              Set<WebhookEventType> events, Instant createdAt, Instant updatedAt,
                              String status, int attempts, String lastError,
                              String signatureVersion) {

    public static WebhookResponse from(MerchantWebhook webhook) {
        return from(webhook, 0, null);
    }

    public static WebhookResponse from(MerchantWebhook webhook, int attempts, String lastError) {
        return new WebhookResponse(
                webhook.id(), webhook.url(), webhook.secret() != null && !webhook.secret().isBlank(),
                webhook.active(), webhook.events(), webhook.createdAt(), webhook.updatedAt(),
                webhook.active() ? "ACTIVE" : "INACTIVE", attempts, lastError,
                webhook.signatureVersion());
    }
}
