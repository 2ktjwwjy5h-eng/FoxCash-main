package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.domain.port.in.InvoiceCollectionUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Job diario: cobra faturas nao quitadas e vencidas no wallet do lojista
 * (Spec 33) e quita receivables de estorno com o saldo disponivel. O ShedLock
 * evita execucao duplicada quando ha mais de uma instancia.
 * Spec 53: execucao delegada ao {@link JobExecutionService}.
 */
@Component
public class InvoiceCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceCollectionScheduler.class);

    private final JobExecutionService jobs;
    private final InvoiceCollectionUseCase invoiceCollection;

    public InvoiceCollectionScheduler(JobExecutionService jobs,
                                      InvoiceCollectionUseCase invoiceCollection) {
        this.jobs = jobs;
        this.invoiceCollection = invoiceCollection;
    }

    @Scheduled(cron = "${foxcash.scheduler.invoice-collection-cron:0 30 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "foxcash-invoice-collection", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void collectDueInvoices() {
        jobs.run("foxcash-invoice-collection", () -> {
            Instant now = Instant.now();
            InvoiceCollectionUseCase.CollectionResult result = invoiceCollection.collectDueInvoices(now);
            log.info("Scheduler de cobranca de faturas: faturas processadas={}, divida de estorno quitada",
                    result.invoicesCollected());
        });
    }
}
