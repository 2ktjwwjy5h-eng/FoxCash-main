package com.foxcash.application.service;

import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.infrastructure.config.SecurityAlertProperties;
import com.foxcash.infrastructure.security.InMemoryAlertCounterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Testes do {@link SecurityAlertPublisher} (Spec 64): contagem de rajada por
 * (tipo, contexto) na janela, throttle anti-spam por tipo, publicacao em
 * transacao propria (REQUIRES_NEW via TransactionTemplate) e fail-safe (uma
 * falha de publicacao nunca derruba o chamador). Payload mascarado: o envio
 * usa apenas o contexto (source/group/path/reason), nunca IP/senha/segredo.
 */
class SecurityAlertPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final WebhookEventPublisher eventPublisher = mock(WebhookEventPublisher.class);
    private SecurityAlertProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SecurityAlertProperties();
        // Throttle desligado para isolar a contagem de janela nos testes abaixo
        // (os testes de throttle ligam explicitamente).
        properties.setThrottleSeconds(0);
    }

    private SecurityAlertPublisher publisher() {
        return new SecurityAlertPublisher(eventPublisher, properties,
                mock(PlatformTransactionManager.class), CLOCK,
                // Store real em memória (Spec 66 Fase B): o contador de rajada
                // vive na porta — os testes de janela/limiar continuam válidos.
                new InMemoryAlertCounterStore(CLOCK));
    }

    private void assertPublished(WebhookEventType type, Map<String, Object> data) {
        ArgumentCaptor<Map> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publish(isNull(), eq(type), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).isEqualTo(data);
    }

    @Test
    void loginFailureBurstPublishesOnceWhenThresholdReached() {
        SecurityAlertPublisher publisher = publisher();

        // 9 falhas: abaixo do limiar (10) — nada publicado.
        for (int i = 0; i < 9; i++) {
            publisher.recordLoginFailure("master");
        }
        verify(eventPublisher, never()).publish(any(), any(), any());

        // 10ª falha atinge o limiar → publica 1 evento de plataforma (merchantId null).
        publisher.recordLoginFailure("master");
        assertPublished(WebhookEventType.LOGIN_FAILURE_BURST, Map.of("source", "master"));
    }

    @Test
    void loginFailureCountersAreIsolatedBySource() {
        SecurityAlertProperties.LoginFailure cfg = properties.getLoginFailure();
        cfg.setThreshold(2);
        SecurityAlertPublisher publisher = publisher();

        publisher.recordLoginFailure("master");
        publisher.recordLoginFailure("merchant");
        // Cada fonte tem contador próprio: nenhuma atingiu o limiar (2).
        verify(eventPublisher, never()).publish(any(), any(), any());

        publisher.recordLoginFailure("merchant");
        assertPublished(WebhookEventType.LOGIN_FAILURE_BURST, Map.of("source", "merchant"));
    }

    @Test
    void rateLimitBurstPublishesWithGroup() {
        SecurityAlertProperties.RateLimit cfg = properties.getRateLimit();
        cfg.setThreshold(3);
        SecurityAlertPublisher publisher = publisher();

        for (int i = 0; i < 3; i++) {
            publisher.recordRateLimitBurst("transactional");
        }
        assertPublished(WebhookEventType.RATE_LIMIT_BURST, Map.of("group", "transactional"));
    }

    @Test
    void signatureFailurePublishesWithPath() {
        SecurityAlertProperties.SignatureFailure cfg = properties.getSignatureFailure();
        cfg.setThreshold(2);
        SecurityAlertPublisher publisher = publisher();

        publisher.recordSignatureFailure("/webhooks/pix");
        verify(eventPublisher, never()).publish(any(), any(), any());

        publisher.recordSignatureFailure("/webhooks/pix");
        assertPublished(WebhookEventType.SIGNATURE_FAILURE_BURST, Map.of("path", "/webhooks/pix"));
    }

    @Test
    void bodyInspectionBlockedPublishesMaskedPayload() {
        SecurityAlertProperties.BodyInspection cfg = properties.getBodyInspection();
        cfg.setThreshold(2);
        SecurityAlertPublisher publisher = publisher();

        publisher.recordBodyInspectionBlocked("/api/v1/merchants", "union-select", "sql_injection");
        verify(eventPublisher, never()).publish(any(), any(), any());

        publisher.recordBodyInspectionBlocked("/api/v1/merchants", "script-tag", "xss");
        // Payload mascarado: path + padrao + categoria (sem corpo/IP/email).
        assertPublished(WebhookEventType.BODY_INSPECTION_BLOCKED,
                Map.of("path", "/api/v1/merchants", "pattern", "script-tag", "category", "xss"));
    }

    @Test
    void failOpenPublishesDirectlyWithoutCounting() {
        SecurityAlertPublisher publisher = publisher();

        publisher.recordRateLimitFailOpen("redis-indisponivel");
        assertPublished(WebhookEventType.RATE_LIMIT_FAIL_OPEN, Map.of("reason", "redis-indisponivel"));
    }

    @Test
    void throttleSuppressesRepeatedAlertsWithinWindow() {
        properties.setThrottleSeconds(300);
        SecurityAlertPublisher publisher = publisher();

        // Primeira rajada publica; segunda rajada dentro da janela é suprimida.
        for (int i = 0; i < 10; i++) {
            publisher.recordLoginFailure("master");
        }
        for (int i = 0; i < 10; i++) {
            publisher.recordLoginFailure("master");
        }

        verify(eventPublisher, times(1)).publish(isNull(),
                eq(WebhookEventType.LOGIN_FAILURE_BURST), any());
    }

    @Test
    void disabledAlertDoesNotPublish() {
        properties.setEnabled(false);
        SecurityAlertPublisher publisher = publisher();

        publisher.recordLoginFailure("master");
        publisher.recordRateLimitFailOpen("redis-indisponivel");

        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void publishFailureNeverPropagatesToCaller() {
        SecurityAlertPublisher publisher = publisher();
        doThrow(new IllegalStateException("outbox indisponivel"))
                .when(eventPublisher).publish(isNull(), any(), any());

        // Fail-safe: o alerta nunca derruba o caminho critico (venda/login).
        assertThatCode(() -> publisher.recordLoginFailure("master")).doesNotThrowAnyException();
    }
}
