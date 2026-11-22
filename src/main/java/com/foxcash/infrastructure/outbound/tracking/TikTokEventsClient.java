package com.foxcash.infrastructure.outbound.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.tracking.ProviderResult;
import com.foxcash.domain.model.tracking.TrackingEvent;
import com.foxcash.domain.model.tracking.TrackingServiceType;
import com.foxcash.domain.port.out.TrackingProviderClient;
import com.foxcash.infrastructure.config.TrackingProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapter da TikTok Events API (Spec 81/D6) — POST
 * {@code /open_api/v1.3/pixel/track/} (header {@code Access-Token}) com
 * {@code event}/{@code user}/{@code properties} (value/currency) e
 * {@code context} (UTMs).
 */
@Component
public class TikTokEventsClient extends AbstractTrackingProviderClient implements TrackingProviderClient {

    private final TrackingProperties properties;

    public TikTokEventsClient(ObjectMapper objectMapper, TrackingProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public TrackingServiceType type() {
        return TrackingServiceType.TIKTOK;
    }

    @Override
    public ProviderResult send(TrackingEvent event, ProviderCredentials credentials) {
        if (credentials.pixelId() == null || credentials.token() == null
                || credentials.token().isBlank()) {
            return ProviderResult.failed("tiktok: pixel_id/token nao configurados");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pixel_code", credentials.pixelId());
        payload.put("event", event.eventType());
        payload.put("event_id", event.eventId().toString());
        payload.put("event_time", event.eventTime().getEpochSecond());
        if (event.platformUserId() != null && !event.platformUserId().isBlank()) {
            payload.put("user", Map.of("external_id", event.platformUserId()));
        }
        Map<String, Object> propertiesMap = new LinkedHashMap<>();
        if (event.value() != null) {
            propertiesMap.put("value", event.value());
            propertiesMap.put("currency", event.currency() == null ? "BRL" : event.currency());
        }
        if (event.utm() != null && event.utm().hasAny()) {
            payload.put("context", utmContext(event));
        }
        payload.put("properties", propertiesMap);
        String url = properties.getTiktokBaseUrl() + "/pixel/track/";
        return postJson(url, Map.of("Access-Token", credentials.token()), payload);
    }

    @Override
    public ProviderResult testConnection(ProviderCredentials credentials) {
        if (credentials.pixelId() == null || credentials.token() == null
                || credentials.token().isBlank()) {
            return ProviderResult.failed("tiktok: pixel_id/token nao configurados");
        }
        // TikTok: POST de teste com test_event_code (não polui métricas reais).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pixel_code", credentials.pixelId());
        payload.put("event", "TestEventCode");
        payload.put("test_event_code", "TEST");
        payload.put("event_time", java.time.Instant.now().getEpochSecond());
        String url = properties.getTiktokBaseUrl() + "/pixel/track/";
        return postJson(url, Map.of("Access-Token", credentials.token()), payload);
    }

    private Map<String, Object> utmContext(TrackingEvent event) {
        Map<String, Object> utm = new LinkedHashMap<>();
        utm.put("utm_source", event.utm().source());
        utm.put("utm_medium", event.utm().medium());
        utm.put("utm_campaign", event.utm().campaign());
        utm.put("utm_content", event.utm().content());
        utm.put("utm_term", event.utm().term());
        return Map.of("ad", utm);
    }
}
