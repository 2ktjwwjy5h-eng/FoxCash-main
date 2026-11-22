package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;

import java.util.UUID;

/**
 * Porta de entrada: reconciliação e integridade do saldo (Spec 30).
 *
 * <p>{@code run()} valida, para cada lojista, que
 * {@code merchants.wallet_balance == balance_after} da última entry do ledger
 * (Spec 27) e registra issues {@code OPEN} nas divergências (idempotente).
 * Também contabiliza cobranças órfãs (transações {@code PENDING} sem
 * {@code externalReference} — timeout da Spec 28) para visibilidade do master.
 */
public interface WalletReconciliationUseCase {

    ReconciliationReport run();

    /** Issues paginadas com filtros opcionais (merchantId/status). */
    PageResult<ReconciliationIssue> findIssues(UUID merchantId, ReconciliationIssueStatus status,
                                               int page, int size);

    /** Resumo do run: quantos lojistas checados/OK, quantas issues e órfãs. */
    record ReconciliationReport(int checked, int ok, int issues, long orphanCharges) {
    }
}
