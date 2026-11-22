package com.foxcash.application.service;

import com.foxcash.domain.model.job.JobRun;
import com.foxcash.domain.model.job.JobRunStatus;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.port.out.JobRunRepository;
import com.foxcash.infrastructure.config.NotificationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Testes do {@link JobExecutionService} (Spec 53): sucesso registra timer +
 * counter success + trilha {@code job_runs}; falha registra counter failure +
 * trilha FAILED com erro + alerta {@code JOB_FAILED} no outbox (apenas quando o
 * webhook de operador esta configurado); falha da propria infra de trilha nao
 * quebra a execucao (nunca re-propaga).
 */
class JobExecutionServiceTest {

    private SimpleMeterRegistry meters;
    private JobRunRepository jobRuns;
    private WebhookEventPublisher eventPublisher;
    private NotificationProperties properties;
    private JobExecutionService service;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        jobRuns = mock(JobRunRepository.class);
        eventPublisher = mock(WebhookEventPublisher.class);
        properties = new NotificationProperties();
        service = new JobExecutionService(meters, jobRuns, eventPublisher, properties);
    }

    @Test
    void successRecordsTimerCounterAndTrailWithoutAlert() {
        service.run("foxcash-test", () -> {
        });

        assertThat(meters.counter("foxcash.job.success", "job", "foxcash-test").count()).isEqualTo(1);
        assertThat(meters.counter("foxcash.job.failure", "job", "foxcash-test").count()).isZero();
        assertThat(meters.find("foxcash.job.duration").timer()).isNotNull();
        assertThat(meters.find("foxcash.job.duration").timer().count()).isEqualTo(1);

        ArgumentCaptor<JobRun> captor = ArgumentCaptor.forClass(JobRun.class);
        verify(jobRuns).save(captor.capture());
        assertThat(captor.getValue().job()).isEqualTo("foxcash-test");
        assertThat(captor.getValue().status()).isEqualTo(JobRunStatus.SUCCESS);
        assertThat(captor.getValue().error()).isNull();
        assertThat(captor.getValue().durationMs()).isGreaterThanOrEqualTo(0);
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void failureRecordsCounterTrailAndAlertsWhenOperatorConfigured() {
        properties.setOperatorWebhookUrl("https://ops.example.com/hook");
        properties.setOperatorWebhookSecret("ops-secret");

        service.run("foxcash-test", () -> {
            throw new IllegalStateException("boom");
        });

        assertThat(meters.counter("foxcash.job.failure", "job", "foxcash-test").count()).isEqualTo(1);
        assertThat(meters.counter("foxcash.job.success", "job", "foxcash-test").count()).isZero();

        ArgumentCaptor<JobRun> runCaptor = ArgumentCaptor.forClass(JobRun.class);
        verify(jobRuns).save(runCaptor.capture());
        assertThat(runCaptor.getValue().status()).isEqualTo(JobRunStatus.FAILED);
        assertThat(runCaptor.getValue().error()).contains("boom");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publish(eq(null), eq(WebhookEventType.JOB_FAILED), dataCaptor.capture());
        assertThat(dataCaptor.getValue().get("job")).isEqualTo("foxcash-test");
        assertThat(dataCaptor.getValue().get("error")).isEqualTo("boom");
    }

    @Test
    void failureDoesNotAlertWhenOperatorNotConfigured() {
        // properties default: operatorWebhookUrl vazio -> fail-open (sem outbox).
        service.run("foxcash-test", () -> {
            throw new IllegalStateException("boom");
        });

        verify(eventPublisher, never()).publish(any(), any(), any());
        assertThat(meters.counter("foxcash.job.failure", "job", "foxcash-test").count()).isEqualTo(1);
        ArgumentCaptor<JobRun> captor = ArgumentCaptor.forClass(JobRun.class);
        verify(jobRuns).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(JobRunStatus.FAILED);
    }

    @Test
    void infraFailureInTrailDoesNotBreakExecution() {
        // Falha da propria infra de trilha (ex.: banco indisponivel) nao
        // re-propaga — o job agendado nao derruba a thread do pool.
        doThrow(new RuntimeException("db down")).when(jobRuns).save(any(JobRun.class));

        assertThatCode(() -> service.run("foxcash-test", () -> {
        })).doesNotThrowAnyException();
        assertThat(meters.counter("foxcash.job.success", "job", "foxcash-test").count()).isEqualTo(1);
    }

    @Test
    void errorMessageFallsBackToClassSimpleNameWhenBlank() {
        properties.setOperatorWebhookUrl("https://ops.example.com/hook");
        properties.setOperatorWebhookSecret("ops-secret");

        service.run("foxcash-test", () -> {
            throw new IllegalStateException((String) null);
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publish(eq(null), eq(WebhookEventType.JOB_FAILED), dataCaptor.capture());
        assertThat(dataCaptor.getValue().get("error")).isEqualTo("IllegalStateException");
    }
}
