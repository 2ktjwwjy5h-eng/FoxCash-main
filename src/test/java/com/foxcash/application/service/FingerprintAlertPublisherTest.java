package com.foxcash.application.service;

import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.infrastructure.config.FingerprintProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Testes do alerta de device fingerprint em múltiplas contas (Spec 141) — o
 * mesmo hash em {@code alert-threshold} lojistas distintos publica
 * {@code FINGERPRINT_DEVICE_SHARED} no outbox (throttled por hash, fail-safe).
 */
class FingerprintAlertPublisherTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    private final WebhookEventPublisher eventPublisher = mock(WebhookEventPublisher.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private FingerprintProperties properties;
    private FingerprintAlertPublisher publisher;

    @BeforeEach
    void setUp() {
        // TransactionTemplate real (usa o txManager mock; REQUIRES_NEW).
        properties = new FingerprintProperties();
        properties.setAlertThreshold(2);
        properties.setAlertThrottleSeconds(0); // sem throttle nos testes de disparo
        publisher = new FingerprintAlertPublisher(eventPublisher, properties, txManager, clock);
    }

    @Test
    void belowThresholdDoesNotPublish() {
        publisher.maybeAlert(HASH, 1);

        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void atThresholdPublishesWithHashAndCount() {
        publisher.maybeAlert(HASH, 2);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publish(eq(null), eq(WebhookEventType.FINGERPRINT_DEVICE_SHARED),
                captor.capture());
        assertThat(captor.getValue()).containsEntry("fingerprintHash", HASH)
                .containsEntry("distinctMerchants", 2);
    }

    @Test
    void throttleByHashSuppressesRepeatWithinWindow() {
        properties.setAlertThrottleSeconds(300);
        publisher.maybeAlert(HASH, 2); // dispara
        publisher.maybeAlert(HASH, 3); // throttled (mesmo hash, mesma janela)

        verify(eventPublisher, times(1)).publish(any(), any(), any());
    }

    @Test
    void disabledDoesNotPublish() {
        properties.setEnabled(false);

        publisher.maybeAlert(HASH, 5);

        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void thresholdZeroNeverAlerts() {
        properties.setAlertThreshold(0);

        publisher.maybeAlert(HASH, 5);

        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void publisherFailureIsSilent() {
        // Fail-safe: exceção na publicação (ex.: tx) nunca propaga ao chamador.
        org.mockito.Mockito.doThrow(new RuntimeException("outbox down"))
                .when(eventPublisher).publish(any(), any(), any());

        // Não deve lançar — o cadastro/login segue normal.
        publisher.maybeAlert(HASH, 2);
    }
}
