package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.application.service.PspSweepService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Job do sweep zero-balance do saldo do PSP (Spec 92): transfere o excedente
 * (Disponível − buffer) da conta master para a conta blindada corporativa.
 * ShedLock impede execução duplicada entre instâncias; cron configurável
 * ({@code foxcash.psp.sweep.cron}, default 06:00 UTC); kill-switch
 * {@code foxcash.psp.sweep.enabled} (default false — o bean só existe quando
 * ligado). Spec 53: execução delegada ao {@link JobExecutionService}
 * (job_runs + métricas foxcash_job_* + alerta JOB_FAILED); falhas específicas
 * publicam {@code SWEEP_FAILED}/{@code SWEEP_EXPOSURE_HIGH} (Spec 92).
 */
@Component
@ConditionalOnProperty(prefix = "foxcash.psp.sweep", name = "enabled", havingValue = "true")
public class PspSweepScheduler {

    private final JobExecutionService jobs;
    private final PspSweepService sweepService;

    public PspSweepScheduler(JobExecutionService jobs, PspSweepService sweepService) {
        this.jobs = jobs;
        this.sweepService = sweepService;
    }

    @Scheduled(cron = "${foxcash.psp.sweep.cron:0 0 6 * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-psp-sweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        jobs.run("foxcash-psp-sweep", () -> sweepService.sweep(Instant.now()));
    }
}
