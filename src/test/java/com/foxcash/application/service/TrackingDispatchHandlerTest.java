package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.tracking.EventLog;
import com.foxcash.domain.model.tracking.EventLogStatus;
import com.foxcash.domain.model.tracking.ProviderResult;
import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.model.tracking.TrackingServiceType;
import com.foxcash.domain.model.tracking.TrackingStatus;
import com.foxcash.domain.port.out.EventLogRepository;
import com.foxcash.domain.port.out.TrackingProfileRepository;
import com.foxcash.domain.port.out.TrackingProviderClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Testes do {@link TrackingDispatchHandler} (Spec 81/D5.2). */
class TrackingDispatchHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID BOT_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TrackingProfileRepository profileRepository = mock(TrackingProfileRepository.class);
    private final EventLogRepository eventLogRepository = mock(EventLogRepository.class);
    private final TrackingEventLogRecorder errorRecorder = mock(TrackingEventLogRecorder.class);
    private final TrackingProviderClient facebook = mock(TrackingProviderClient.class);
    private final TrackingProviderClient tiktok = mock(TrackingProviderClient.class);

    private TrackingDispatchHandler handler;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Os mocks precisam do type() ANTES de montar o mapa de clientes do handler.
        when(facebook.type()).thenReturn(TrackingServiceType.FACEBOOK);
        when(tiktok.type()).thenReturn(TrackingServiceType.TIKTOK);
        handler = new TrackingDispatchHandler(
                objectMapper, profileRepository, eventLogRepository, errorRecorder,
                List.of(facebook, tiktok), new SimpleMeterRegistry());
    }

    private TrackingProfile profile(boolean fb, boolean tk) {
        return TrackingProfile.restore(PROFILE_ID, MERCHANT, "Pixel Modelo", TrackingStatus.ACTIVE,
                fb ? "PIXEL_FB" : null, fb ? "tok-fb" : null,
                tk ? "PIXEL_TK" : null, tk ? "tok-tk" : null,
                null, null, NOW, NOW);
    }

    private OutboxNotification notification(Map<String, Object> data) throws Exception {
        WebhookEventPublisher.WebhookEnvelope envelope = new WebhookEventPublisher.WebhookEnvelope(
                UUID.randomUUID(), WebhookEventType.TRACKING_DISPATCH, MERCHANT, Instant.now(), data);
        return OutboxNotification.pending(MERCHANT, WebhookEventType.TRACKING_DISPATCH.name(),
                objectMapper.writeValueAsString(envelope));
    }

    private Map<String, Object> data(String eventType) {
        return Map.of("profileId", PROFILE_ID.toString(), "eventType", eventType,
                "eventId", EVENT_ID.toString(), "botId", BOT_ID.toString(),
                "platformUserId", "u-1", "value", "49.90", "currency", "BRL");
    }

    @Test
    void allProvidersOkRecordsEventLogOk() throws Exception {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile(true, true)));
        when(facebook.send(any(), any())).thenReturn(ProviderResult.ok("ok"));
        when(tiktok.send(any(), any())).thenReturn(ProviderResult.ok("ok"));

        handler.process(notification(data("PURCHASE")));

        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventLogRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(EventLogStatus.OK);
        assertThat(captor.getValue().profileId()).isEqualTo(PROFILE_ID);
        assertThat(captor.getValue().eventId()).isEqualTo(EVENT_ID);
        verify(errorRecorder, never()).recordError(any(), any(), any(), any());
    }

    @Test
    void anyProviderFailureRecordsErrorAndThrows() throws Exception {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile(true, true)));
        when(facebook.send(any(), any())).thenReturn(ProviderResult.ok("ok"));
        when(tiktok.send(any(), any())).thenReturn(ProviderResult.failed("HTTP 401"));

        assertThatThrownBy(() -> handler.process(notification(data("PURCHASE"))))
                .isInstanceOf(TrackingDispatchHandler.TrackingProviderException.class)
                .hasMessageContaining("tiktok");

        verify(errorRecorder).recordError(eq(PROFILE_ID), eq("PURCHASE"), eq(EVENT_ID), any());
        verify(eventLogRepository, never()).save(any());
    }

    @Test
    void missingProfileIsNoOp() throws Exception {
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());

        handler.process(notification(data("LEAD")));

        verify(eventLogRepository, never()).save(any());
        verify(errorRecorder, never()).recordError(any(), any(), any(), any());
    }

    @Test
    void inactiveProfileIsNoOp() throws Exception {
        TrackingProfile inactive = TrackingProfile.restore(PROFILE_ID, MERCHANT, "X",
                TrackingStatus.INACTIVE, "PIXEL", "tok", null, null, null, null, NOW, NOW);
        when(profileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(inactive));

        handler.process(notification(data("LEAD")));

        verify(eventLogRepository, never()).save(any());
    }
}
