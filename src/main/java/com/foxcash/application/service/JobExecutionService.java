package com.foxcash.application.service;

import com.foxcash.domain.model.job.JobRun;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.JobRunRepository;
import com.foxcash.infrastructure.config.NotificationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Abstracao unica de execucao de jobs (Spec 53): cronometra, registra metrica
 * Micrometer ({@code foxcash.job.*}), grava a trilha {@code job_runs} e, em falha,
 * publica {@code JOB_FAILED} no outbox (webhook de operador, Spec 35).
 *
 * <p>Os 6 schedulers delegam a {@link #run(String, Runnable)} — aqui a falha e
 * capturada (log estruturado + metrica + trilha + alerta) e NAO re-propagada:
 * um job agendado nao derruba a thread do pool. Falha da propria infra de
 * trilha/alerta tambem nao quebra a execucao (WARN + segue).
 */
@Service
public class JobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionService.class);

    private static final int MAX_ERROR_LENGTH = 500;

    private final MeterRegistry meterRegistry;
    private final JobRunRepository jobRuns;
    private final WebhookEventPublisher eventPublisher;
    private final NotificationProperties notificationProperties;

    public JobExecutionService(MeterRegistry meterRegistry,
                               JobRunRepository jobRuns,
                               WebhookEventPublisher eventPublisher,
                               NotificationProperties notificationProperties) {
        this.meterRegistry = meterRegistry;
        this.jobRuns = jobRuns;
        this.eventPublisher = eventPublisher;
        this.notificationProperties = notificationProperties;
    }

    /**
     * Executa o job, cronometra e registra metrica/trilha; em falha, captura e
     * alerta (outbox) — nunca re-propaga.
     */
    public void run(String jobName, Runnable task) {
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        try {
            task.run();
            finish(jobName, startedAt, startedNanos, null);
        } catch (Exception e) {
            finish(jobName, startedAt, startedNanos, e);
        }
    }

    private void finish(String jobName, Instant startedAt, long startedNanos, Exception error) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        // Timer registrado por nome+tags — register() e idempotente.
        Timer.builder("foxcash.job.duration")
                .tag("job", jobName)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        if (error == null) {
            meterRegistry.counter("foxcash.job.success", "job", jobName).increment();
            log.info("job={} status=SUCCESS duration_ms={}", jobName, durationMs);
            recordRun(jobName, startedAt, durationMs, null);
        } else {
            String message = errorMessage(error);
            meterRegistry.counter("foxcash.job.failure", "job", jobName).increment();
            log.error("job={} status=FAILED duration_ms={} error={}", jobName, durationMs, message);
            recordRun(jobName, startedAt, durationMs, message);
            publishJobFailed(jobName, startedAt, message);
        }
    }

    private void recordRun(String jobName, Instant startedAt, long durationMs, String error) {
        try {
            JobRun run = error == null
                    ? JobRun.success(jobName, startedAt, Instant.now(), durationMs)
                    : JobRun.failed(jobName, startedAt, Instant.now(), durationMs, error);
            jobRuns.save(run);
        } catch (Exception e) {
            log.warn("Falha ao gravar job_runs para job={}: {}", jobName, e.getMessage());
        }
    }

    private void publishJobFailed(String jobName, Instant startedAt, String error) {
        if (!notificationProperties.isOperatorWebhookConfigured()) {
            return;
        }
        try {
            eventPublisher.publish(null, WebhookEventType.JOB_FAILED, Map.of(
                    "job", jobName,
                    "startedAt", startedAt.toString(),
                    "error", error));
        } catch (Exception e) {
            log.warn("Falha ao publicar JOB_FAILED para job={}: {}", jobName, e.getMessage());
        }
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
