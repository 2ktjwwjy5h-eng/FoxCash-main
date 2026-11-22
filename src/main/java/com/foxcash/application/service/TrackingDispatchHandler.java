package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.botfunnel.UtmParams;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.tracking.EventLog;
import com.foxcash.domain.model.tracking.ProviderResult;
import com.foxcash.domain.model.tracking.TrackingEvent;
import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.model.tracking.TrackingServiceType;
import com.foxcash.domain.model.tracking.TrackingStatus;
import com.foxcash.domain.port.out.EventLogRepository;
import com.foxcash.domain.port.out.TrackingProfileRepository;
import com.foxcash.domain.port.out.TrackingProviderClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consome os eventos {@code TRACKING_DISPATCH} do outbox (Spec 81/D5.2) —
 * interceptado pelo {@link NotificationProcessor} ANTES do {@code MerchantNotifier}.
 * Para cada perfil: envia aos provedores configurados e grava {@code event_logs}
 * (idempotente). Falha de UM provedor → {@code event_logs} ERROR (transação
 * própria) + exceção → retry/backoff do outbox (max tentativas, OUTBOX_DEAD).
 */
@Service
public class TrackingDispatchHandler {

    private static final Logger log = LoggerFactory.getLogger(TrackingDispatchHandler.class);

    private final ObjectMapper objectMapper;
    private final TrackingProfileRepository profileRepository;
    private final EventLogRepository eventLogRepository;
    private final TrackingEventLogRecorder errorRecorder;
    private final Map<TrackingServiceType, TrackingProviderClient> clients;
    private final MeterRegistry meterRegistry;

    public TrackingDispatchHandler(ObjectMapper objectMapper,
                                   TrackingProfileRepository profileRepository,
                                   EventLogRepository eventLogRepository,
                                   TrackingEventLogRecorder errorRecorder,
                                   List<TrackingProviderClient> providerClients,
                                   MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.profileRepository = profileRepository;
        this.eventLogRepository = eventLogRepository;
        this.errorRecorder = errorRecorder;
        this.clients = providerClients.stream()
                .collect(Collectors.toUnmodifiableMap(TrackingProviderClient::type, Function.identity()));
        this.meterRegistry = meterRegistry;
    }

    public boolean handles(String type) {
        return WebhookEventType.TRACKING_DISPATCH.name().equals(type);
    }

    /**
     * Processa UM evento de dispatch (na transação do {@link NotificationProcessor}).
     * Lança exceção se algum provedor falhar → o processor faz retry/backoff.
     *
     * <p>{@code noRollbackFor}: a exceção de provedor NÃO pode marcar a transação
     * compartilhada como rollback-only (senão o {@code NotificationProcessor} reverte
     * o FAILED/backoff em {@code handleFailure} e o commit lança
     * {@code UnexpectedRollbackException} — auditoria 2026-08-16). O erro persiste no
     * {@code event_logs} via {@link TrackingEventLogRecorder} (REQUIRES_NEW).
     */
    @Transactional(noRollbackFor = TrackingProviderException.class)
    public void process(OutboxNotification notification) {
        Map<String, Object> data = parseData(notification);
        UUID profileId = uuid(data, "profileId");
        String eventType = string(data, "eventType");
        UUID eventId = uuid(data, "eventId");
        UUID botId = uuid(data, "botId");
        UUID merchantId = notification.merchantId();

        TrackingProfile profile = profileRepository.findById(profileId).orElse(null);
        if (profile == null || profile.status() != TrackingStatus.ACTIVE) {
            // Perfil removido/inativado após o publish — no-op (o job é drenado).
            log.info("Tracking dispatch ignorado: profile={} status={} (removido/inativo)",
                    profileId, profile == null ? "GONE" : profile.status());
            return;
        }

        TrackingEvent event = buildEvent(profile, eventType, eventId, botId, merchantId, data);
        List<ConfiguredProvider> configured = configuredProviders(profile);
        if (configured.isEmpty()) {
            return; // perfil sem provedor configurado — no-op
        }

        boolean allOk = true;
        String firstError = null;
        for (ConfiguredProvider provider : configured) {
            // Spec 81/D9: métrica de latência por provedor (Timer).
            Timer.Sample sample = Timer.start(meterRegistry);
            ProviderResult result = sendQuiet(provider, event);
            sample.stop(Timer.builder("foxcash_tracking_latency_seconds")
                    .tag("provider", provider.service().name().toLowerCase())
                    .register(meterRegistry));
            meterRegistry.counter("foxcash_tracking_sent_total", "provider", provider.service().name().toLowerCase())
                    .increment();
            if (result.ok()) {
                meterRegistry.counter("foxcash_tracking_ok_total", "provider", provider.service().name().toLowerCase())
                        .increment();
            } else {
                allOk = false;
                if (firstError == null) {
                    firstError = provider.service().name().toLowerCase() + ": " + result.message();
                }
                meterRegistry.counter("foxcash_tracking_error_total", "provider", provider.service().name().toLowerCase())
                        .increment();
                log.warn("Tracking {} falhou no provedor {} (event={} profile={}): {}",
                        eventType, provider.service(), eventId, profileId, result.message());
            }
        }

        if (allOk) {
            eventLogRepository.save(EventLog.ok(profileId, eventType, eventId));
        } else {
            // ERROR persiste mesmo com o retry (REQUIRES_NEW); a exceção agenda o backoff.
            errorRecorder.recordError(profileId, eventType, eventId, firstError);
            throw new TrackingProviderException(firstError);
        }
    }

    private ProviderResult sendQuiet(ConfiguredProvider provider, TrackingEvent event) {
        try {
            return provider.client().send(event, provider.credentials());
        } catch (Exception e) {
            return ProviderResult.failed(e.getMessage());
        }
    }

    private List<ConfiguredProvider> configuredProviders(TrackingProfile profile) {
        java.util.List<ConfiguredProvider> result = new java.util.ArrayList<>();
        if (profile.isFacebookConfigured()) {
            result.add(new ConfiguredProvider(TrackingServiceType.FACEBOOK,
                    clients.get(TrackingServiceType.FACEBOOK),
                    new TrackingProviderClient.ProviderCredentials(profile.facebookPixelId(),
                            profile.facebookAccessToken())));
        }
        if (profile.isTikTokConfigured()) {
            result.add(new ConfiguredProvider(TrackingServiceType.TIKTOK,
                    clients.get(TrackingServiceType.TIKTOK),
                    new TrackingProviderClient.ProviderCredentials(profile.tiktokPixelId(),
                            profile.tiktokAccessToken())));
        }
        if (profile.isUtmifyConfigured()) {
            result.add(new ConfiguredProvider(TrackingServiceType.UTMIFY,
                    clients.get(TrackingServiceType.UTMIFY),
                    new TrackingProviderClient.ProviderCredentials(null, profile.utmifyToken())));
        }
        if (profile.isOtimizeyConfigured()) {
            result.add(new ConfiguredProvider(TrackingServiceType.OTIMIZEY,
                    clients.get(TrackingServiceType.OTIMIZEY),
                    new TrackingProviderClient.ProviderCredentials(null, profile.otimizeyToken())));
        }
        return result;
    }

    private TrackingEvent buildEvent(TrackingProfile profile, String eventType, UUID eventId,
                                     UUID botId, UUID merchantId, Map<String, Object> data) {
        String valueRaw = string(data, "value");
        BigDecimal value = valueRaw == null ? null : new BigDecimal(valueRaw);
        UtmParams utm = UtmParams.of(
                string(data, "utmSource"), string(data, "utmMedium"),
                string(data, "utmCampaign"), string(data, "utmContent"),
                string(data, "utmTerm"));
        return new TrackingEvent(profile.id(), merchantId, botId, eventType, eventId,
                string(data, "platformUserId"), value,
                string(data, "currency") == null ? "BRL" : string(data, "currency"),
                utm.hasAny() ? utm : null, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseData(OutboxNotification notification) {
        try {
            WebhookEventPublisher.WebhookEnvelope envelope = objectMapper.readValue(
                    notification.payload(), WebhookEventPublisher.WebhookEnvelope.class);
            return envelope.data() instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
        } catch (Exception e) {
            throw new IllegalStateException("Payload invalido de TRACKING_DISPATCH: " + e.getMessage(), e);
        }
    }

    private static UUID uuid(Map<String, Object> data, String key) {
        String raw = string(data, key);
        return raw == null ? null : UUID.fromString(raw);
    }

    private static String string(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? null : value.toString();
    }

    /** Falha de envio a um provedor (retry/backoff do outbox — Spec 81/D5.2). */
    public static class TrackingProviderException extends RuntimeException {
        public TrackingProviderException(String message) {
            super(message);
        }
    }

    private record ConfiguredProvider(TrackingServiceType service,
                                      TrackingProviderClient client,
                                      TrackingProviderClient.ProviderCredentials credentials) {
    }
}
