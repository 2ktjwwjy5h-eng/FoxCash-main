package com.foxcash.application.service;

import com.foxcash.domain.model.tracking.EventLog;
import com.foxcash.domain.port.out.EventLogRepository;
import com.foxcash.infrastructure.config.TrackingProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Grava o {@code event_logs} ERROR de um disparo de tracking em transação
 * PRÓPRIA ({@code REQUIRES_NEW}) — o erro precisa persistir mesmo quando o
 * outbox falha (retry/backoff). Idempotente: upsert por {@code (profile_id,
 * event_id)} (Spec 81/D5.2).
 */
@Service
public class TrackingEventLogRecorder {

    private final EventLogRepository eventLogRepository;
    private final TrackingProperties properties;

    public TrackingEventLogRecorder(EventLogRepository eventLogRepository,
                                    TrackingProperties properties) {
        this.eventLogRepository = eventLogRepository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(UUID profileId, String eventType, UUID eventId, String error) {
        eventLogRepository.save(EventLog.error(profileId, eventType, eventId,
                truncate(error)));
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        int max = properties.getEventLogResponseMax();
        return error.length() > max ? error.substring(0, max) : error;
    }
}
