package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Caso de uso: receita/perda da plataforma (Spec 34) — consultas sobre o
 * {@code master_ledger}. Somente-leitura (ROLE_MASTER).
 */
public interface MasterRevenueUseCase {

    /**
     * Resumo da receita/perda por tipo no período (SQL SUM/GROUP BY).
     * {@code net = entradas (fees + recoveries + adminRecoveries) − losses}.
     */
    RevenueSummary summary(Instant from, Instant to);

    /** Movimentacoes paginadas com filtros (lojista/tipo/período). */
    PageResult<MasterLedgerEntry> ledger(UUID merchantId, MasterLedgerEntryType type,
                                         Instant from, Instant to, int page, int size);

    record RevenueSummary(BigDecimal transactionFees,
                          BigDecimal monthlyFees,
                          BigDecimal recoveries,
                          BigDecimal adminRecoveries,
                          BigDecimal withdrawalFees,
                          BigDecimal losses,
                          BigDecimal total,
                          BigDecimal net) {
    }
}
