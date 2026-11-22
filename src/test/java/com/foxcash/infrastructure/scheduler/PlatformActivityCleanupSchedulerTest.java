package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.infrastructure.config.SchedulerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PlatformActivityCleanupScheduler} (Spec 95) — retenção
 * opt-in da trilha {@code platform_activity_log}: deleta eventos mais antigos
 * que {@code retention-days} via {@code deleteOlderThan} e registra o job no
 * {@link JobExecutionService} (padrão Spec 53).
 */
class PlatformActivityCleanupSchedulerTest {

    private final JobExecutionService jobs = mock(JobExecutionService.class);
    private final PlatformActivityRepository activityRepository = mock(PlatformActivityRepository.class);
    private SchedulerProperties properties;
    private PlatformActivityCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        scheduler = new PlatformActivityCleanupScheduler(jobs, activityRepository, properties);
        // O JobExecutionService executa o Runnable (mesmo comportamento real).
        doAnswer(i -> {
            ((Runnable) i.getArgument(1)).run();
            return null;
        }).when(jobs).run(eq("foxcash-activity-log-cleanup"), org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void cleanupDeletesEventsOlderThanRetentionDays() {
        when(activityRepository.deleteOlderThan(org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(42);

        scheduler.cleanup();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(activityRepository).deleteOlderThan(cutoffCaptor.capture());
        // Default 180 dias: cutoff ≈ now − 180d (tolerância de 1s de execução).
        Instant cutoff = cutoffCaptor.getValue();
        assertThat(cutoff).isAfter(Instant.now().minus(Duration.ofDays(181)));
        assertThat(cutoff).isBefore(Instant.now().minus(Duration.ofDays(179)));

        // Job registrado no JobExecutionService (trilha job_runs + métricas).
        verify(jobs).run(eq("foxcash-activity-log-cleanup"), org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void cleanupUsesConfiguredRetentionDays() {
        properties.getActivityLog().setRetentionDays(30);

        scheduler.cleanup();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(activityRepository).deleteOlderThan(cutoffCaptor.capture());
        Instant cutoff = cutoffCaptor.getValue();
        assertThat(cutoff).isAfter(Instant.now().minus(Duration.ofDays(31)));
        assertThat(cutoff).isBefore(Instant.now().minus(Duration.ofDays(29)));
    }
}
