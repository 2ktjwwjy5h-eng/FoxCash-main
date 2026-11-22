package com.foxcash.application.service;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.notification.WebhookEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Testes do {@link HoneypotAlertPublisher} (Spec 98 — D4.5): cada cobrança de
 * lojista honeypot publica {@code HONEYPOT_TRIGGERED} no outbox com THROTTLE por
 * janela (padrão Spec 64) + trilha de atividade (Spec 41, actor SYSTEM). A 2ª
 * tentativa na mesma janela NÃO publica de novo (sem spam ao operador).
 */
class HoneypotAlertPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private final WebhookEventPublisher eventPublisher = mock(WebhookEventPublisher.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private HoneypotAlertPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new HoneypotAlertPublisher(eventPublisher, activityRecorder,
                transactionManager, clock);
    }

    @Test
    void recordPublishesEventAndActivity() {
        publisher.record(MERCHANT_ID, TX_ID);

        ArgumentCaptor<Map> data = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.eq(MERCHANT_ID),
                org.mockito.ArgumentMatchers.eq(WebhookEventType.HONEYPOT_TRIGGERED), data.capture());
        assertThat(data.getValue()).containsEntry("merchantId", MERCHANT_ID);
        verify(activityRecorder).record(PlatformActorType.SYSTEM, null,
                PlatformAction.HONEYPOT_TRIGGERED, "TRANSACTION", TX_ID, MERCHANT_ID, Map.of("merchantId", MERCHANT_ID));
    }

    @Test
    void secondAttemptWithinWindowIsThrottled() {
        publisher.record(MERCHANT_ID, TX_ID);
        publisher.record(MERCHANT_ID, UUID.randomUUID()); // mesma janela

        verify(eventPublisher, times(1)).publish(
                org.mockito.ArgumentMatchers.eq(MERCHANT_ID),
                org.mockito.ArgumentMatchers.eq(WebhookEventType.HONEYPOT_TRIGGERED),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishUsesRequiresNewPropagation() {
        // L54 (VoidMethodCallMutator): setPropagationBehavior(REQUIRES_NEW)
        // removido → o TransactionTemplate voltaria ao default REQUIRED. O alerta
        // honeypot roda em transação PRÓPRIA (fail-safe — a cobrança trap nunca é
        // derrubada por rollback do chamador); a definição de transação DEVE
        // propagar PROPAGATION_REQUIRES_NEW.
        publisher.record(MERCHANT_ID, TX_ID);

        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
