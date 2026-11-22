package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.domain.port.in.WalletReconciliationUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de reconciliação do saldo (Spec 30): valida {@code wallet_balance} contra
 * o ledger (Spec 27) e registra issues {@code OPEN} nas divergências.
 * ShedLock evita execução duplicada entre instâncias. Cron configurável
 * ({@code foxcash.scheduler.reconciliation-cron}, default 04:00 UTC).
 * Spec 53: execucao delegada ao {@link JobExecutionService}.
 *
 * <p>Spec 126: registra o gauge {@code foxcash_reconciliation_open_issues} (issues
 * OPEN atuais — avaliado no scrape, sempre fresco; 0 em falha de banco).
 */
@Component
public class WalletReconciliationScheduler {

    private final JobExecutionService jobs;
    private final WalletReconciliationUseCase reconciliation;

    public WalletReconciliationScheduler(JobExecutionService jobs,
                                         WalletReconciliationUseCase reconciliation,
                                         MeterRegistry meterRegistry) {
        this.jobs = jobs;
        this.reconciliation = reconciliation;
        // Gauge avaliado no scrape (lê o banco — valor sempre atual). Captura
        // falha do banco → 0 (o scrape não pode quebrar por métrica).
        meterRegistry.gauge("foxcash_reconciliation_open_issues", this,
                WalletReconciliationScheduler::openIssues);
    }

    /** Issues OPEN atuais; 0 em falha (o scrape não pode quebrar por métrica). */
    private long openIssues() {
        try {
            return reconciliation.findIssues(null, ReconciliationIssueStatus.OPEN, 0, 1)
                    .totalElements();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    @Scheduled(cron = "${foxcash.scheduler.reconciliation-cron:0 0 4 * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-wallet-reconciliation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        jobs.run("foxcash-wallet-reconciliation", reconciliation::run);
    }
}
