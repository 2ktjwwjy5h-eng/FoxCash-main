package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.application.service.NotificationDispatcherService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Worker agendado: entrega as notificacoes pendentes do outbox.
 * O ShedLock evita processamento duplicado entre instancias.
 * Spec 53: execucao delegada ao {@link JobExecutionService} — a duracao do poll
 * e as falhas entram na metrica/trilha.
 *
 * <p>Etapa 1 (pos-incidente Fase 2A, docs §11): a run roda numa thread dedicada
 * com WATCHDOG de {@value #RUN_TIMEOUT_MIN}min. Um batch preso (thread em I/O de
 * rede, fora do banco) NUNCA mais deixa o {@code fixedDelay} parado para sempre:
 * ao estourar o timeout, o stack da thread presa e logado (diagnostico) e o ciclo
 * seguinte segue — o lock PT5M expira e outra instancia assume
 * (at-least-once + idempotencia cobre overlap).
 */
@Component
public class NotificationDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchScheduler.class);

    /** Limite de uma run (normal: 1-2s p/ lote de 200 sem webhooks; pior caso ~30s). */
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(2);
    private static final long RUN_TIMEOUT_MIN = 2;

    private final JobExecutionService jobs;
    private final NotificationDispatcherService dispatcher;

    /** Thread da run (daemon): o watchdog nao depende da thread do scheduler. */
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "foxcash-dispatch-worker");
        t.setDaemon(true);
        return t;
    });

    public NotificationDispatchScheduler(JobExecutionService jobs,
                                         NotificationDispatcherService dispatcher) {
        this.jobs = jobs;
        this.dispatcher = dispatcher;
    }

    // Spec 62 F8 + Spec 65: intervalo configurável por env (poll mais frequente
    // = outbox drena mais rapido; fixedDelay conta a partir do fim da execucao
    // anterior). Default PT10S (meio-termo pos-incidente: 30s era lento demais
    // para drenar; 3s da Spec 65 agrediria o DB com 0 backlog).
    @Scheduled(fixedDelayString = "${foxcash.notification.poll-interval:PT10S}",
            initialDelayString = "${foxcash.notification.poll-initial-delay:PT10S}")
    // Etapa 1 (pos-incidente Fase 2A 2026-08-09): lockAtMostFor=PT5M (era PT40M).
    // Uma run pode durar mais que o lock (lote ≤ 500 eventos x 10s de timeout
    // HTTP, em loop de ate max-batches-per-run lotes), mas o lock CURTO garante
    // takeover rapido de batch preso — nenhuma instancia segura o lease por
    // 40min. Overlap entre ciclos e coberto por at-least-once + idempotencia do
    // receptor (X-FoxCash-Event-Id).
    @SchedulerLock(name = "foxcash-notification-dispatch", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void dispatchPending() {
        jobs.run("foxcash-notification-dispatch", () -> {
            Future<?> future = worker.submit(() -> {
                int delivered = dispatcher.dispatchPending();
                if (delivered > 0) {
                    log.info("Dispatcher de notificacoes: {} evento(s) entregue(s)", delivered);
                }
            });
            try {
                future.get(RUN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Watchdog: run presa alem do limite — loga o stack (diagnostico)
                // e libera o scheduler. O lock PT5M expira e o proximo ciclo (ou
                // outra instancia) assume; at-least-once cobre qualquer overlap.
                log.error("DISPATCH_WATCHDOG: run presa apos {}min. Stack: [{}]",
                        RUN_TIMEOUT_MIN, dumpStack());
                future.cancel(true);
            } catch (java.util.concurrent.ExecutionException e) {
                // A run lancou (inesperado) — o jobs.run registra como FAILED.
                log.error("DISPATCH_WATCHDOG: run falhou inesperadamente", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Stack da thread worker presa — usado pelo watchdog para apontar a causa. */
    private static String dumpStack() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> entry
                : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (thread.getName().startsWith("foxcash-dispatch-worker")) {
                sb.append(thread.getName()).append(" state=").append(thread.getState()).append('\n');
                for (StackTraceElement el : entry.getValue()) {
                    sb.append("    at ").append(el).append('\n');
                }
            }
        }
        return sb.length() == 0 ? "worker nao encontrada (ja finalizada)" : sb.toString();
    }
}
