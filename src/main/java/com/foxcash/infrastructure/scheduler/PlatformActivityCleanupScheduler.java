package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.infrastructure.config.SchedulerProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Retenção da trilha {@code platform_activity_log} (Spec 95) — **opt-in por
 * config** ({@code foxcash.scheduler.activity-log-cleanup-enabled=true}):
 * apaga eventos mais antigos que {@code retention-days} (default 180).
 *
 * <p>Espelho do {@link JobRunsCleanupScheduler} (Spec 53) — ShedLock +
 * {@link JobExecutionService}. **Default DESLIGADO**: o contrato append-only da
 * Spec 41 permanece (a API não ganha rota de mutação); o operador habilita
 * quando o volume da trilha exigir. Sem migration nova — o delete usa o índice
 * {@code ix_platform_activity_created} existente (V33).
 */
@Component
@ConditionalOnProperty(prefix = "foxcash.scheduler",
        name = "activity-log-cleanup-enabled", havingValue = "true")
public class PlatformActivityCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlatformActivityCleanupScheduler.class);

    private final JobExecutionService jobs;
    private final PlatformActivityRepository activityRepository;
    private final SchedulerProperties properties;

    public PlatformActivityCleanupScheduler(JobExecutionService jobs,
                                            PlatformActivityRepository activityRepository,
                                            SchedulerProperties properties) {
        this.jobs = jobs;
        this.activityRepository = activityRepository;
        this.properties = properties;
    }

    @Scheduled(cron = "${foxcash.scheduler.activity-log-cleanup-cron:0 15 4 * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-activity-log-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        jobs.run("foxcash-activity-log-cleanup", () -> {
            Instant cutoff = Instant.now()
                    .minus(Duration.ofDays(properties.getActivityLog().getRetentionDays()));
            int deleted = activityRepository.deleteOlderThan(cutoff);
            log.info("Limpeza de platform_activity_log: {} registro(s) anteriores a {} removidos",
                    deleted, cutoff);
        });
    }
}
