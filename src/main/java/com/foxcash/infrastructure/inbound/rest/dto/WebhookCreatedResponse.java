package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.application.service.WebhookManagementService.CreatedWebhook;
import com.foxcash.domain.model.notification.WebhookEventType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Resposta de criacao de webhook (Spec 35): {@code secret} devolvido UMA unica
 * vez no 201 — nas demais respostas o segredo nunca e exposto.
 */
public record WebhookCreatedResponse(UUID id, String url, String secret, boolean active,
                                     Set<WebhookEventType> events, Instant createdAt,
                                     Instant updatedAt) {

    public static WebhookCreatedResponse from(CreatedWebhook created) {
        return new WebhookCreatedResponse(
                created.webhook().id(), created.webhook().url(), created.secret(),
                created.webhook().active(), created.webhook().events(),
                created.webhook().createdAt(), created.webhook().updatedAt());
    }
}
