package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.domain.port.in.GenerateInvoicesForPeriodUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Job mensal: gera a fatura do mes anterior para todos os lojistas ACTIVE.
 * O ShedLock evita execucao duplicada quando ha mais de uma instancia.
 * Spec 53: a execucao delega ao {@link JobExecutionService} (metrica + trilha
 * {@code job_runs} + alerta {@code JOB_FAILED} em falha).
 */
@Component
public class InvoiceGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceGenerationScheduler.class);

    private final JobExecutionService jobs;
    private final GenerateInvoicesForPeriodUseCase generateInvoicesForPeriod;

    public InvoiceGenerationScheduler(JobExecutionService jobs,
                                      GenerateInvoicesForPeriodUseCase generateInvoicesForPeriod) {
        this.jobs = jobs;
        this.generateInvoicesForPeriod = generateInvoicesForPeriod;
    }

    @Scheduled(cron = "${foxcash.scheduler.invoice-cron:0 5 0 1 * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-invoice-generation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void generatePreviousMonthInvoices() {
        jobs.run("foxcash-invoice-generation", () -> {
            YearMonth period = YearMonth.now(ZoneOffset.UTC).minusMonths(1);
            int generated = generateInvoicesForPeriod.generateForPeriod(period);
            log.info("Scheduler de faturas: periodo={}, faturas geradas={}", period, generated);
        });
    }
}
