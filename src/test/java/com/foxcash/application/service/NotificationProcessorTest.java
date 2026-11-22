package com.foxcash.application.service;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.MerchantNotifier;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.infrastructure.config.NotificationProperties;
import com.foxcash.infrastructure.notification.PermanentWebhookDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link NotificationProcessor} (auditoria 2026-08-05): cada
 * notificacao e processada na propria transacao — sucesso marca {@code SENT},
 * falha agenda retry/backoff (ou {@code FAILED}) usando o contador de
 * tentativas REAL retornado pelo incremento (nao o valor stale carregado no
 * inicio do lote).
 */
class NotificationProcessorTest {

    private final NotificationOutboxPort outbox = mock(NotificationOutboxPort.class);
    private final MerchantNotifier notifier = mock(MerchantNotifier.class);
    private final NotificationProperties properties = new NotificationProperties();
    private final WebhookEventPublisher eventPublisher = mock(WebhookEventPublisher.class);
    private final TrackingDispatchHandler trackingDispatchHandler = mock(TrackingDispatchHandler.class);
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private NotificationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new NotificationProcessor(outbox, notifier, properties, eventPublisher,
                trackingDispatchHandler, meterRegistry);
    }

    @Test
    void trackingDispatchIsInterceptedBeforeNotifier() {
        // Spec 81: TRACKING_DISPATCH NUNCA chega ao MerchantNotifier — o handler
        // processa e o evento é marcado SENT.
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(),
                WebhookEventType.TRACKING_DISPATCH.name(), "{\"data\":{}}");
        when(trackingDispatchHandler.handles(WebhookEventType.TRACKING_DISPATCH.name()))
                .thenReturn(true);

        boolean delivered = processor.process(n);

        assertTrue(delivered);
        verify(trackingDispatchHandler).process(n);
        verify(notifier, never()).deliver(any());
        verify(outbox).markSent(n.id());
    }

    @Test
    void deliverSuccessMarksSentAndReturnsTrue() {
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p");

        boolean delivered = processor.process(n);

        assertTrue(delivered);
        verify(notifier).deliver(n);
        verify(outbox).markSent(n.id());
        verify(outbox, never()).incrementAttempts(any());
        // Spec 126: entrega contabilizada no counter delivered.
        assertEquals(1, meterRegistry.counter("foxcash_outbox_delivered_total").count());
        assertEquals(0, meterRegistry.counter("foxcash_outbox_failed_total").count());
    }

    @Test
    void failureSchedulesBackoffUsingIncrementedAttempts() {
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p");
        properties.setMaxAttempts(3);
        properties.setBackoffBaseMs(30_000);
        doThrow(new IllegalStateException("HTTP 500")).when(notifier).deliver(n);
        when(outbox.incrementAttempts(n.id())).thenReturn(1); // 1a falha

        boolean delivered = processor.process(n);

        assertFalse(delivered);
        verify(outbox, never()).markSent(any());
        verify(outbox).incrementAttempts(n.id());
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(outbox).markFailed(eq(n.id()), any(String.class), captor.capture());
        long diffMillis = captor.getValue().toEpochMilli() - Instant.now().toEpochMilli();
        assertEquals(60_000, diffMillis, 5_000); // 30000 * 2^1
        // Spec 126: falha contabilizada no counter failed (delivered não muda).
        assertEquals(1, meterRegistry.counter("foxcash_outbox_failed_total").count());
        assertEquals(0, meterRegistry.counter("foxcash_outbox_delivered_total").count());
    }

    @Test
    void failureAtMaxAttemptsMarksFailedWithoutNextAttempt() {
        OutboxNotification n = new OutboxNotification(UUID.randomUUID(), UUID.randomUUID(),
                "SALE_PAID", "p", Instant.now(), 2, null, null); // attempts=2, max=3
        properties.setMaxAttempts(3);
        doThrow(new IllegalStateException("HTTP 500")).when(notifier).deliver(n);
        when(outbox.incrementAttempts(n.id())).thenReturn(3); // 2+1 = 3 >= max

        boolean delivered = processor.process(n);

        assertFalse(delivered);
        verify(outbox).markFailed(eq(n.id()), any(String.class), eq(null));
        // Spec 35/53: esgotou → alerta OUTBOX_DEAD (plataforma) na mesma transação.
        verify(eventPublisher).publish(isNull(), eq(WebhookEventType.OUTBOX_DEAD), any());
    }

    @Test
    void permanent4xxMarksFailedImmediatelyWithoutBackoff() {
        // Spec 148: 4xx do receptor é falha PERMANENTE (erro do cliente) — o
        // processor encerra na hora (FAILED, sem retryAt), MESMO com tentativas
        // restantes (retry não resolve: a config do receptor está errada).
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p");
        properties.setMaxAttempts(5);
        doThrow(new PermanentWebhookDeliveryException("Webhook https://x/hook rejeitado (4xx): HTTP 400"))
                .when(notifier).deliver(n);
        when(outbox.incrementAttempts(n.id())).thenReturn(1); // só 1a tentativa

        boolean delivered = processor.process(n);

        assertFalse(delivered);
        verify(outbox).markFailed(eq(n.id()), any(String.class), eq(null)); // sem backoff
        verify(eventPublisher).publish(isNull(), eq(WebhookEventType.OUTBOX_DEAD), any());
    }

    @Test
    void failureUsesRealIncrementedCountNotStaleValue() {
        // Regressao (auditoria 2026-08-05): o notification carregado tem
        // attempts=0 (stale), mas o incremento REAL devolve 4 (>= max 3) =>
        // FAILED. Antes o dispatcher usava 0+1=1 < 3 e agenda retry mesmo
        // quando a notificacao ja havia estourado o maximo em outro processo.
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p"); // attempts=0
        properties.setMaxAttempts(3);
        doThrow(new IllegalStateException("HTTP 500")).when(notifier).deliver(n);
        when(outbox.incrementAttempts(n.id())).thenReturn(4); // contagem real

        boolean delivered = processor.process(n);

        assertFalse(delivered);
        verify(outbox, times(1)).markFailed(eq(n.id()), any(String.class), isNull()); // FAILED, sem retry
    }

    // ---------- round 10 (mutation: errorMessage L70) ----------

    @Test
    void failureWithNullMessageUsesClassSimpleName() {
        // Mutations (NegateConditionals + EmptyObjectReturnVals L70): exception
        // sem mensagem → o erro gravado é o nome da classe. O mutante de
        // `message == null` → `!= null` estouraria NPE em isBlank(); o
        // EmptyObjectReturnVals gravaria "".
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p");
        properties.setMaxAttempts(3);
        doThrow(new IllegalStateException((String) null)).when(notifier).deliver(n);
        when(outbox.incrementAttempts(n.id())).thenReturn(1);

        boolean delivered = processor.process(n);

        assertFalse(delivered);
        verify(outbox).markFailed(eq(n.id()), eq("IllegalStateException"), any(Instant.class));
    }

    @Test
    void failureWithBlankMessageUsesClassSimpleName() {
        // Mutation (NegateConditionals L70, isBlank negada): mensagem em branco
        // → nome da classe (mutante gravaria o texto em branco).
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p");
        properties.setMaxAttempts(3);
        doThrow(new IllegalStateException("   ")).when(notifier).deliver(n);
        when(outbox.incrementAttempts(n.id())).thenReturn(1);

        boolean delivered = processor.process(n);

        assertFalse(delivered);
        verify(outbox).markFailed(eq(n.id()), eq("IllegalStateException"), any(Instant.class));
    }

    // ---------- Spec 35/53: OUTBOX_DEAD (alerta de evento esgotado) ----------

    @Test
    void failureAtMaxPublishesDeadLetterWithEventDetails() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OutboxNotification n = new OutboxNotification(eventId, merchantId,
                "SALE_PAID", "p", Instant.now(), 4, null, null); // attempts=4, max=5
        properties.setMaxAttempts(5);
        doThrow(new IllegalStateException("HTTP 500")).when(notifier).deliver(n);
        when(outbox.incrementAttempts(eventId)).thenReturn(5); // 4+1 = 5 >= max

        boolean delivered = processor.process(n);

        assertFalse(delivered);
        verify(outbox).markFailed(eq(eventId), any(String.class), eq(null));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publish(isNull(), eq(WebhookEventType.OUTBOX_DEAD), dataCaptor.capture());
        Map<String, Object> data = dataCaptor.getValue();
        assertEquals(eventId.toString(), data.get("eventId"));
        assertEquals("SALE_PAID", data.get("originalType"));
        assertEquals(merchantId.toString(), data.get("merchantId"));
        assertTrue(data.get("error").toString().contains("500"));
        assertEquals(5, data.get("attempts"));
    }

    @Test
    void failureWithRetryLeftDoesNotPublishDeadLetter() {
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p");
        properties.setMaxAttempts(5);
        doThrow(new IllegalStateException("HTTP 500")).when(notifier).deliver(n);
        when(outbox.incrementAttempts(n.id())).thenReturn(1); // ainda há retry

        processor.process(n);

        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void deadLetterThatDiesDoesNotPublishAnotherDeadLetter() {
        // Anti-cascata: um OUTBOX_DEAD que esgota as tentativas NÃO gera outro —
        // evita cadeia infinita de alertas sobre alertas.
        UUID eventId = UUID.randomUUID();
        OutboxNotification n = new OutboxNotification(eventId, null,
                "OUTBOX_DEAD", "p", Instant.now(), 4, null, null); // attempts=4, max=5
        properties.setMaxAttempts(5);
        doThrow(new IllegalStateException("HTTP 500")).when(notifier).deliver(n);
        when(outbox.incrementAttempts(eventId)).thenReturn(5);

        boolean delivered = processor.process(n);

        assertFalse(delivered);
        verify(outbox).markFailed(eq(eventId), any(String.class), eq(null));
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    // ---------- Spec 127: MDC traceId no worker (correlação de logs) ----------

    @Test
    void transactionEventSetsMdcTraceIdDuringProcessingAndClearsIt() {
        // O worker loga com traceId=%X{traceId:-}: durante o process de um evento
        // de TRANSACTION o MDC carrega o entityId; o finally remove — a thread do
        // pool não vaza o campo.
        UUID traceId = UUID.randomUUID();
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p")
                .withReference("TRANSACTION", traceId);
        AtomicReference<String> mdcDuringProcess = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringProcess.set(MDC.get("traceId"));
            return null;
        }).when(notifier).deliver(n);

        processor.process(n);

        assertEquals(traceId.toString(), mdcDuringProcess.get());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void eventWithoutReferenceDoesNotSetMdc() {
        // OUTBOX_DEAD/JOB_FAILED não têm referência — o MDC fica limpo (sem poluição).
        OutboxNotification n = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p");
        AtomicReference<String> mdcDuringProcess = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringProcess.set(MDC.get("traceId"));
            return null;
        }).when(notifier).deliver(n);

        processor.process(n);

        assertNull(mdcDuringProcess.get());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void mdcTraceIdDoesNotLeakBetweenProcessings() {
        // Evento com referência seguido de evento sem: o finally removeu o traceId
        // do 1º — o 2º process não vê campo residual na thread do pool.
        UUID traceId = UUID.randomUUID();
        OutboxNotification tx = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p")
                .withReference("TRANSACTION", traceId);
        OutboxNotification plain = OutboxNotification.pending(UUID.randomUUID(), "SALE_PAID", "p");
        AtomicReference<String> mdcOnPlain = new AtomicReference<>();
        doAnswer(inv -> {
            mdcOnPlain.set(MDC.get("traceId"));
            return null;
        }).when(notifier).deliver(plain);

        processor.process(tx);
        processor.process(plain);

        assertNull(mdcOnPlain.get());
        assertNull(MDC.get("traceId"));
    }
}
