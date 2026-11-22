package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.infrastructure.notification.HttpMerchantNotifier.DeliveryResult;

/**
 * Resultado do teste de entrega de webhook (Spec 35).
 */
public record WebhookTestResponse(boolean delivered, Integer statusCode, String error) {

    public static WebhookTestResponse from(DeliveryResult result) {
        return new WebhookTestResponse(result.delivered(), result.statusCode(), result.error());
    }
}
