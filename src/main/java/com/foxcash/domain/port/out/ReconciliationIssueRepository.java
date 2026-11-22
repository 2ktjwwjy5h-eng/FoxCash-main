package com.foxcash.domain.port.out;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta de saída: persistência de issues de reconciliação (Spec 30).
 *
 * <p>{@code open} é IDEMPOTENTE: o job nunca duplica a issue de um lojista
 * divergente (unique parcial {@code uq_reconciliation_open} onde
 * {@code status = 'OPEN'}) — a issue original é preservada.
 */
public interface ReconciliationIssueRepository {

    /**
     * Registra (ou reaproveita) a issue ABERTA do lojista divergente.
     */
    ReconciliationIssue open(UUID merchantId, Money expectedBalance, Money actualBalance, Instant now);

    /** Issues paginadas com filtros opcionais (merchantId/status). */
    PageResult<ReconciliationIssue> find(UUID merchantId, ReconciliationIssueStatus status,
                                         int page, int size);
}
