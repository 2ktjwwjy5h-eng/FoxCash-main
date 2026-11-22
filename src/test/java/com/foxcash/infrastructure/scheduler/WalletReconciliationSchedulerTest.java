package com.foxcash.infrastructure.scheduler;

import com.foxcash.application.service.JobExecutionService;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.domain.port.in.WalletReconciliationUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do gauge de issues OPEN da reconciliacao (Spec 126 — C4): o
 * {@link WalletReconciliationScheduler} registra
 * {@code foxcash_reconciliation_open_issues} (avaliado no scrape — le o count de
 * issues OPEN via {@link WalletReconciliationUseCase#findIssues}; 0 em falha de
 * banco). Espec 30: divergencia de saldo aberta = alerta critico.
 */
class WalletReconciliationSchedulerTest {

    private final JobExecutionService jobs = mock(JobExecutionService.class);
    private final WalletReconciliationUseCase reconciliation = mock(WalletReconciliationUseCase.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void openIssuesGaugeReflectsOpenIssueCount() {
        new WalletReconciliationScheduler(jobs, reconciliation, meterRegistry);
        when(reconciliation.findIssues(isNull(), eq(ReconciliationIssueStatus.OPEN), eq(0), eq(1)))
                .thenReturn(PageResult.<ReconciliationIssue>of(List.of(), 0, 1, 3));

        // Gauge avaliado no scrape — reflete as issues OPEN atuais.
        assertThat(meterRegistry.get("foxcash_reconciliation_open_issues").gauge().value()).isEqualTo(3);
    }

    @Test
    void openIssuesGaugeIsZeroWhenNoOpenIssues() {
        new WalletReconciliationScheduler(jobs, reconciliation, meterRegistry);
        when(reconciliation.findIssues(isNull(), eq(ReconciliationIssueStatus.OPEN), eq(0), eq(1)))
                .thenReturn(PageResult.<ReconciliationIssue>of(List.of(), 0, 1, 0));

        assertThat(meterRegistry.get("foxcash_reconciliation_open_issues").gauge().value()).isZero();
    }
}
