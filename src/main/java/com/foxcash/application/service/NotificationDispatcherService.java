package com.foxcash.application.service;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.port.out.NotificationOutboxPort;
import com.foxcash.infrastructure.config.NotificationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Aplicacao: entrega os eventos pendentes do outbox (Spec 35).
 *
 * <p>Orquestra apenas: busca o lote (limitado por
 * {@code foxcash.notification.max-batch-size}) e delega CADA notificacao ao
 * {@link NotificationProcessor}, que roda na SUA propria transacao (auditoria
 * 2026-08-05). Antes o lote inteiro rodava em uma unica transacao longa com
 * I/O de rede — falha de banco no meio revertia tudo e reentregava eventos ja
 * enviados, e o lote prendia uma conexao do pool por minutos.
 *
 * <p>Spec 126: registra o gauge {@code foxcash_outbox_backlog_age_seconds} (idade do
 * PENDING mais antigo; 0 quando vazio) — avaliado no scrape, sempre fresco.
 */
@Service
public class NotificationDispatcherService {

    private final NotificationOutboxPort outbox;
    private final NotificationProcessor processor;
    private final NotificationProperties properties;

    public NotificationDispatcherService(NotificationOutboxPort outbox,
                                         NotificationProcessor processor,
                                         NotificationProperties properties,
                                         MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.processor = processor;
        this.properties = properties;
        // Gauge avaliado no scrape (lê o banco — valor sempre atual). Captura
        // falha do banco → 0 (o scrape não pode quebrar por métrica).
        meterRegistry.gauge("foxcash_outbox_backlog_age_seconds", this,
                NotificationDispatcherService::backlogAgeSeconds);
    }

    /** Idade (s) do PENDING mais antigo; 0 quando não há backlog ou em falha. */
    private long backlogAgeSeconds() {
        try {
            return outbox.findOldestPendingCreatedAt()
                    .map(oldest -> Duration.between(oldest, Instant.now()).getSeconds())
                    .orElse(0L);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * Drena o outbox em loop (Spec 65 — Fase 3): processa ate
     * {@code max-batches-per-run} lotes dentro da MESMA run (mesmo lock
     * ShedLock), parando quando o backlog esvazia ou o watchdog interrompe a
     * thread. {@code SENT} e idempotente — o loop nunca duplica entrega. O lote
     * limite por iteracao continua {@code max-batch-size} (teto por query).
     *
     * <p>Spec 134 (modo dreno): com
     * {@code foxcash.notification.worker-enabled=false} (fila RabbitMQ ativa e
     * backlog zerado), o worker NÃO processa — a entrega passa a ser pelo
     * {@code OutboxConsumer}; o outbox DB segue como fonte da verdade
     * (fail-safe). Rollback = religar a flag.
     */
    public int dispatchPending() {
        if (!properties.isWorkerEnabled()) {
            // Modo dreno desligado: a fila RabbitMQ entrega (Spec 134). O gauge
            // de backlog continua avaliado — o operador vê a fila fluindo.
            return 0;
        }
        int delivered = 0;
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            if (Thread.currentThread().isInterrupted()) {
                // Watchdog (Spec 53) cancelou a run — para limpo; o proximo ciclo
                // retoma o restante (at-least-once + idempotencia).
                Thread.currentThread().interrupt();
                break;
            }
            // Re-consulta a cada lote: o backlog drena e os deadlines dos
            // retries vencem (next_attempt_at) entre as iteracoes.
            Instant now = Instant.now();
            List<OutboxNotification> pending = outbox.findPendingDue(now, properties.getMaxBatchSize());
            if (pending.isEmpty()) {
                break;
            }
            for (OutboxNotification notification : pending) {
                if (processor.process(notification)) {
                    delivered++;
                }
            }
        }
        return delivered;
    }
}
