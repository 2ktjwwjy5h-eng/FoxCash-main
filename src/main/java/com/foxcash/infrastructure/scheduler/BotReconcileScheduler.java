package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.application.service.StoreBotIntegrationService;
import com.foxcash.infrastructure.config.BotProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de reconciliação dos bots de venda (Spec 74/D9) — ShedLock +
 * {@link JobExecutionService} (Spec 53: métrica + trilha {@code job_runs} +
 * alerta {@code JOB_FAILED}). Revalida registros presos em {@code REGISTERING}
 * (→ {@code ERROR}), bots {@code ACTIVE} sem health recente e bots
 * {@code ERROR} com token válido (re-integra). Kill-switch
 * {@code foxcash.bots.reconcile-enabled}.
 */
@Component
public class BotReconcileScheduler {

    private final JobExecutionService jobs;
    private final StoreBotIntegrationService integrationService;
    private final BotProperties properties;

    public BotReconcileScheduler(JobExecutionService jobs,
                                 StoreBotIntegrationService integrationService,
                                 BotProperties properties) {
        this.jobs = jobs;
        this.integrationService = integrationService;
        this.properties = properties;
    }

    @Scheduled(cron = "${foxcash.bots.reconcile-cron:0 */5 * * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-bot-reconcile", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void reconcileBots() {
        if (!properties.isReconcileEnabled()) {
            return;
        }
        jobs.run("foxcash-bot-reconcile", integrationService::reconcile);
    }
}
