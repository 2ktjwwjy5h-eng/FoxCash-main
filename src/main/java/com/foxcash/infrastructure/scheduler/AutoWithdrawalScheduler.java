package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.AutoWithdrawRunnerService;
import com.foxcash.application.service.JobExecutionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Job do saque automatico por limiar de saldo (Spec 70): para cada config ativa
 * com {@code wallet_balance >= threshold} (sob lock do lojista) cria um saque
 * no valor do saldo total. ShedLock impede execucao duplicada entre instancias.
 * Cron configuravel ({@code foxcash.auto-withdraw.cron}, default 5 min UTC);
 * kill-switch global {@code foxcash.auto-withdraw.enabled}. Spec 53: execucao
 * delegada ao {@link JobExecutionService} (job_runs + metricas foxcash_job_* +
 * alerta JOB_FAILED).
 */
@Component
public class AutoWithdrawalScheduler {

    private final JobExecutionService jobs;
    private final AutoWithdrawRunnerService runner;

    public AutoWithdrawalScheduler(JobExecutionService jobs,
                                   AutoWithdrawRunnerService runner) {
        this.jobs = jobs;
        this.runner = runner;
    }

    @Scheduled(cron = "${foxcash.auto-withdraw.cron:0 */5 * * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-auto-withdraw", lockAtMostFor = "PT15M", lockAtLeastFor = "PT30S")
    public void run() {
        jobs.run("foxcash-auto-withdraw", () -> runner.runAll(Instant.now()));
    }
}
