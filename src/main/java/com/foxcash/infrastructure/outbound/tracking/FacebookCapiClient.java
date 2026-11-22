package com.foxcash.infrastructure.outbound.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.tracking.ProviderResult;
import com.foxcash.domain.model.tracking.TrackingEvent;
import com.foxcash.domain.model.tracking.TrackingServiceType;
import com.foxcash.domain.port.out.TrackingProviderClient;
import com.foxcash.infrastructure.config.TrackingProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

/**
 * Adapter da Facebook Conversions API v19 (Spec 81/D6) — POST
 * {@code /{version}/{pixel_id}/events} com {@code user_data} hasheado
 * (external_id = SHA-256 do ID opaco do usuário) e {@code custom_data}
 * value/currency BRL.
 */
@Component
public class FacebookCapiClient extends AbstractTrackingProviderClient implements TrackingProviderClient {

    private final TrackingProperties properties;

    public FacebookCapiClient(ObjectMapper objectMapper, TrackingProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public TrackingServiceType type() {
        return TrackingServiceType.FACEBOOK;
    }

    @Override
    public ProviderResult send(TrackingEvent event, ProviderCredentials credentials) {
        if (credentials.pixelId() == null || credentials.token() == null
                || credentials.token().isBlank()) {
            return ProviderResult.failed("facebook: pixel_id/token nao configurados");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event_name", event.eventType());
        data.put("event_time", event.eventTime().getEpochSecond());
        data.put("action_source", "chat");
        data.put("event_id", event.eventId().toString());
        if (event.platformUserId() != null && !event.platformUserId().isBlank()) {
            data.put("user_data", Map.of("external_id", sha256(event.platformUserId())));
        }
        if (event.value() != null) {
            data.put("custom_data", Map.of("value", event.value(), "currency", "BRL"));
        }
        String url = properties.getCapiBaseUrl() + "/" + properties.getCapiApiVersion()
                + "/" + credentials.pixelId() + "/events";
        Map<String, Object> body = Map.of("data", List.of(data));
        return postJson(url, Map.of("Authorization", "Bearer " + credentials.token()), body);
    }

    @Override
    public ProviderResult testConnection(ProviderCredentials credentials) {
        if (credentials.pixelId() == null || credentials.token() == null
                || credentials.token().isBlank()) {
            return ProviderResult.failed("facebook: pixel_id/token nao configurados");
        }
        String url = properties.getCapiBaseUrl() + "/" + properties.getCapiApiVersion()
                + "/" + credentials.pixelId() + "?access_token=" + credentials.token();
        return get(url, Map.of());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao hashear user_data (CAPI)", e);
        }
    }
}
