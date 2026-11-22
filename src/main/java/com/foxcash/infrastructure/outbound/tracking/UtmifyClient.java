package com.foxcash.infrastructure.outbound.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.tracking.TrackingServiceType;
import com.foxcash.infrastructure.config.TrackingProperties;
import org.springframework.stereotype.Component;

/** Adapter webhook da UTMify (Spec 81/D6). */
@Component
public class UtmifyClient extends AbstractWebhookTrackingClient {

    public UtmifyClient(ObjectMapper objectMapper, TrackingProperties properties) {
        super(objectMapper, properties);
    }

    @Override
    public TrackingServiceType type() {
        return TrackingServiceType.UTMIFY;
    }

    @Override
    protected String baseUrl() {
        return properties.getUtmifyBaseUrl();
    }
}
