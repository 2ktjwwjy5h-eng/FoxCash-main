package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.application.service.WithdrawalReconciliationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Job de reconciliacao de saques PROCESSING orfaos (Spec 36): consulta o PSP e
 * encerra saques presos (COMPLETED/FAILED + estorno). ShedLock evita execucao
 * duplicada entre instancias. Cron configurável
 * ({@code foxcash.psp.payout-reconciliation.cron}, default 04:30 UTC — fora da
 * janela de pico de saques). Spec 53: execucao delegada ao
 * {@link JobExecutionService}.
 */
@Component
public class WithdrawalReconciliationScheduler {

    private final JobExecutionService jobs;
    private final WithdrawalReconciliationService reconciliation;

    public WithdrawalReconciliationScheduler(JobExecutionService jobs,
                                             WithdrawalReconciliationService reconciliation) {
        this.jobs = jobs;
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${foxcash.psp.payout-reconciliation.cron:0 30 4 * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-payout-reconciliation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        jobs.run("foxcash-payout-reconciliation", () -> reconciliation.reconcile(Instant.now()));
    }
}
