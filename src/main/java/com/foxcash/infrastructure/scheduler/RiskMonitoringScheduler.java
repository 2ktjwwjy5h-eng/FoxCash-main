package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.domain.port.in.RiskMonitoringUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job agendado de monitoramento de risco (Spec 16, ADR-008): calcula e grava
 * um snapshot de risco por lojista. ShedLock evita execucao duplicada.
 * Spec 53: execucao delegada ao {@link JobExecutionService}.
 */
@Component
public class RiskMonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(RiskMonitoringScheduler.class);

    private final JobExecutionService jobs;
    private final RiskMonitoringUseCase riskMonitoring;

    public RiskMonitoringScheduler(JobExecutionService jobs, RiskMonitoringUseCase riskMonitoring) {
        this.jobs = jobs;
        this.riskMonitoring = riskMonitoring;
    }

    @Scheduled(cron = "${foxcash.scheduler.risk-cron:0 0 3 * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-risk-monitoring", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void computeRiskSnapshots() {
        jobs.run("foxcash-risk-monitoring", () -> {
            int saved = riskMonitoring.computeForAllMerchants();
            log.info("Monitoramento de risco: snapshots gravados={}", saved);
        });
    }
}
