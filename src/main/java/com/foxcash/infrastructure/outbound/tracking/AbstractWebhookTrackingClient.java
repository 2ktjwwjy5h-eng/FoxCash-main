package com.foxcash.infrastructure.outbound.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.tracking.ProviderResult;
import com.foxcash.domain.model.tracking.TrackingEvent;
import com.foxcash.domain.model.tracking.TrackingServiceType;
import com.foxcash.domain.port.out.TrackingProviderClient;
import com.foxcash.infrastructure.config.TrackingProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base dos clientes webhook UTMify/Otimizey (Spec 81/D6) — POST no endpoint da
 * ferramenta com o token no header {@code Authorization: Bearer} e payload do
 * pedido + UTMs. O shape exato do endpoint é decisão de implementação
 * documentada (o contrato é o {@code POST {baseUrl}/event} + {@code /ping}).
 */
abstract class AbstractWebhookTrackingClient extends AbstractTrackingProviderClient
        implements TrackingProviderClient {

    protected final TrackingProperties properties;

    protected AbstractWebhookTrackingClient(ObjectMapper objectMapper, TrackingProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public ProviderResult send(TrackingEvent event, ProviderCredentials credentials) {
        if (credentials.token() == null || credentials.token().isBlank()) {
            return ProviderResult.failed(type().name().toLowerCase() + ": token nao configurado");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_name", event.eventType());
        payload.put("event_id", event.eventId().toString());
        payload.put("user_id", event.platformUserId());
        if (event.value() != null) {
            payload.put("value", event.value());
            payload.put("currency", event.currency() == null ? "BRL" : event.currency());
        }
        if (event.utm() != null && event.utm().hasAny()) {
            payload.put("utm_source", event.utm().source());
            payload.put("utm_medium", event.utm().medium());
            payload.put("utm_campaign", event.utm().campaign());
            payload.put("utm_content", event.utm().content());
            payload.put("utm_term", event.utm().term());
        }
        String url = baseUrl() + "/event";
        return postJson(url, Map.of("Authorization", "Bearer " + credentials.token()), payload);
    }

    @Override
    public ProviderResult testConnection(ProviderCredentials credentials) {
        if (credentials.token() == null || credentials.token().isBlank()) {
            return ProviderResult.failed(type().name().toLowerCase() + ": token nao configurado");
        }
        String url = baseUrl() + "/ping";
        return postJson(url, Map.of("Authorization", "Bearer " + credentials.token()), Map.of());
    }

    protected abstract String baseUrl();

    @Override
    public abstract TrackingServiceType type();
}
