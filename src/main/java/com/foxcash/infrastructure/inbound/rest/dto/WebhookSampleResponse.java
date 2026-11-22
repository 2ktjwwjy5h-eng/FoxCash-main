package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exemplo do payload de um evento de webhook (Spec 119 — endpoint
 * {@code GET /api/v1/portal/webhooks/{id}/sample?type=}): mock do contrato da
 * Spec 35 (envelope do outbox) com valores placeholder — SEM PII (o
 * {@code buyerIdHash} é uma máscara fixa {@code ******}; nenhum dado real de
 * cliente/comprador é exposto).
 */
public record WebhookSampleResponse(String type, Map<String, Object> payload) {

    public static WebhookSampleResponse forType(String type) {
        String eventType = (type == null || type.isBlank()) ? "SALE_PAID" : type.trim().toUpperCase();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt_8f2c9a1b3d4e5f60718293a4b5c6d7e8");
        payload.put("type", eventType);
        payload.put("merchantId", "00000000-0000-0000-0000-000000000000");
        payload.put("createdAt", "2026-08-16T02:14:00Z");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactionId", "TREVO-2026-08-16-0001");
        data.put("amount", 49.90);
        data.put("status", "PAID");
        // Sem PII: buyerIdHash NUNCA é o hash real — máscara fixa (Spec 35/64).
        data.put("buyerIdHash", "******");
        payload.put("data", data);
        return new WebhookSampleResponse(eventType, payload);
    }
}
