package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.domain.port.out.JobRunRepository;
import com.foxcash.infrastructure.config.SchedulerProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Job diario de limpeza da trilha {@code job_runs} (Spec 53): apaga registros
 * mais antigos que {@code foxcash.scheduler.job-runs.retention-days} (default
 * 30). Tambem delega ao {@link JobExecutionService} — a falha do cleanup entra
 * na metrica/trilha/alerta como qualquer job.
 */
@Component
public class JobRunsCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobRunsCleanupScheduler.class);

    private final JobExecutionService jobs;
    private final JobRunRepository jobRuns;
    private final SchedulerProperties properties;

    public JobRunsCleanupScheduler(JobExecutionService jobs,
                                   JobRunRepository jobRuns,
                                   SchedulerProperties properties) {
        this.jobs = jobs;
        this.jobRuns = jobRuns;
        this.properties = properties;
    }

    @Scheduled(cron = "${foxcash.scheduler.job-runs-cleanup-cron:0 45 4 * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-job-runs-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        jobs.run("foxcash-job-runs-cleanup", () -> {
            Instant cutoff = Instant.now().minus(Duration.ofDays(properties.getJobRuns().getRetentionDays()));
            int deleted = jobRuns.deleteOlderThan(cutoff);
            log.info("Limpeza de job_runs: {} registro(s) anteriores a {} removidos", deleted, cutoff);
        });
    }
}
