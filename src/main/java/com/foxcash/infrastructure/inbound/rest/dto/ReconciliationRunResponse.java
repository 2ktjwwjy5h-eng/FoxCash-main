package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.WalletReconciliationUseCase;

/**
 * Resumo do run de reconciliação (Spec 30): quantos lojistas checados/OK,
 * quantas issues de divergência e cobranças órfãs (PENDING sem
 * externalReference — timeout da Spec 28).
 */
public record ReconciliationRunResponse(
        int checked,
        int ok,
        int issues,
        long orphanCharges) {

    public static ReconciliationRunResponse from(WalletReconciliationUseCase.ReconciliationReport report) {
        return new ReconciliationRunResponse(report.checked(), report.ok(),
                report.issues(), report.orphanCharges());
    }
}
