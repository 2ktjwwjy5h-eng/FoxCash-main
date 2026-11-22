package com.foxcash.application.service;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.MerchantNotifier;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.infrastructure.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Processa UMA notificacao do outbox na propria transacao (auditoria 2026-08-05):
 * entrega HTTP + marca {@code SENT} (ou retry/backoff/{@code FAILED}) de forma
 * atomica. Antes o {@link NotificationDispatcherService} rodava TODAS as
 * notificacoes pendentes em uma unica transacao longa — uma falha de banco no
 * meio revertia o lote inteiro e reentregava eventos ja enviados por HTTP, e o
 * lote prendia uma conexao do pool durante todo o I/O de rede.
 *
 * <p>Entrega com semantica at-least-once: se o {@code markSent} falhar apos o
 * POST com sucesso, a transacao reverte e o evento e reentregue (o receptor
 * deduplica pelo {@code eventId} — Spec 35).
 *
 * <p>Spec 35/53: quando um evento ESGOTA as tentativas ({@code FAILED}), publica
 * um {@code OUTBOX_DEAD} (plataforma, sem merchant) no outbox — entregue ao
 * webhook de operador, alertando que o evento foi perdido (sem DLQ fisica).
 */
@Service
public class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final NotificationOutboxPort outbox;
    private final MerchantNotifier notifier;
    private final NotificationProperties properties;
    private final WebhookEventPublisher eventPublisher;
    private final TrackingDispatchHandler trackingDispatchHandler;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public NotificationProcessor(NotificationOutboxPort outbox,
                                 MerchantNotifier notifier,
                                 NotificationProperties properties,
                                 WebhookEventPublisher eventPublisher,
                                 TrackingDispatchHandler trackingDispatchHandler,
                                 io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.notifier = notifier;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.trackingDispatchHandler = trackingDispatchHandler;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Processa uma notificacao (transacao propria). Retorna {@code true} quando
     * a entrega foi bem-sucedida (marcada {@code SENT}).
     */
    @Transactional
    public boolean process(OutboxNotification notification) {
        // Spec 127: correlação de logs — o worker roda em thread própria (sem
        // RequestIdFilter); seta o traceId de negócio da transação no MDC para o
        // log estruturado (traceId=%X{traceId:-} + Axiom includeMdc) correlacionar
        // o request do bot → webhook do PSP → worker pelo MESMO fio. O finally
        // remove — a thread do pool não vaza MDC entre processamentos.
        String traceId = traceIdOf(notification);
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        try {
            if (trackingDispatchHandler.handles(notification.type())) {
                // Spec 81: evento interno de tracking — NUNCA vai ao MerchantNotifier
                // (o handler envia aos provedores e grava event_logs; lança em falha
                // → retry/backoff abaixo).
                trackingDispatchHandler.process(notification);
            } else {
                notifier.deliver(notification);
            }
            outbox.markSent(notification.id());
            // Spec 126: métrica de entrega do outbox (alerta: rate de falha).
            meterRegistry.counter("foxcash_outbox_delivered_total").increment();
            return true;
        } catch (Exception e) {
            handleFailure(notification, e);
            meterRegistry.counter("foxcash_outbox_failed_total").increment();
            return false;
        } finally {
            if (traceId != null) {
                MDC.remove("traceId");
            }
        }
    }

    /**
     * traceId de negócio do evento (Spec 127): {@code entityId} quando a origem é
     * uma transação (referência da Spec 125 — curada, sem parse do payload JSON).
     * Eventos sem referência (OUTBOX_DEAD, JOB_FAILED) retornam {@code null} — o
     * MDC não é setado (sem poluição).
     */
    private static String traceIdOf(OutboxNotification notification) {
        return "TRANSACTION".equals(notification.entityType()) && notification.entityId() != null
                ? notification.entityId().toString() : null;
    }

    private void handleFailure(OutboxNotification notification, Exception error) {
        // Spec 148: 4xx do receptor é falha PERMANENTE (erro do cliente) —
        // encerra as tentativas imediatamente, sem backoff (retry não resolve).
        boolean permanent = error instanceof com.foxcash.infrastructure.notification.PermanentWebhookDeliveryException;
        // incrementAttempts devolve o NOVO contador (leitura fresca na mesma
        // transacao) — decisao de retry/backoff vs. FAILED sem estado stale.
        int attempts = outbox.incrementAttempts(notification.id());
        String message = errorMessage(error);
        if (permanent || attempts >= properties.getMaxAttempts()) {
            outbox.markFailed(notification.id(), message, null);
            publishDeadLetter(notification, message, attempts);
        } else {
            long backoffMillis = properties.getBackoffBaseMs() * (1L << attempts);
            outbox.markFailed(notification.id(), message, Instant.now().plusMillis(backoffMillis));
        }
    }

    /**
     * Alerta operacional (Spec 35/53): a entrega do evento esgotou as tentativas
     * e virou {@code FAILED} — publica {@code OUTBOX_DEAD} (plataforma, sem
     * merchant → entregue ao webhook de operador) na MESMA transacao, com o
     * merchantId/erro do evento morto no payload. Anti-cascata: um
     * {@code OUTBOX_DEAD} que morre NÃO gera outro {@code OUTBOX_DEAD}.
     */
    private void publishDeadLetter(OutboxNotification notification, String error, int attempts) {
        if (WebhookEventType.OUTBOX_DEAD.name().equals(notification.type())) {
            log.warn("OUTBOX_DEAD esgotou as tentativas (anti-cascata — sem novo alerta): eventId={}",
                    notification.id());
            return;
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("eventId", notification.id().toString());
            data.put("originalType", notification.type());
            data.put("merchantId", notification.merchantId() == null ? null : notification.merchantId().toString());
            data.put("error", error);
            data.put("attempts", attempts);
            eventPublisher.publish(null, WebhookEventType.OUTBOX_DEAD, data);
        } catch (Exception e) {
            // O alerta nao pode quebrar o processamento nem mascarar o erro original.
            log.warn("Falha ao publicar OUTBOX_DEAD para eventId={}: {}", notification.id(), e.getMessage());
        }
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? error.getClass().getSimpleName() : message;
    }
}
