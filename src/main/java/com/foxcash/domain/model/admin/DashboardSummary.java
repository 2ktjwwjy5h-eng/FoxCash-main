package com.foxcash.domain.model.admin;

import com.foxcash.domain.model.Money;

/**
 * Resumo agregado do dashboard administrativo (contagens e somas).
 */
public record DashboardSummary(
        MerchantStats merchants,
        TransactionStats transactions,
        InvoiceStats invoices) {

    /** Contagens de lojistas. */
    public record MerchantStats(long total, long active, long suspended, long inactive) {
    }

    /** Contagens de transações + soma dos valores aprovados. */
    public record TransactionStats(long total, long approved, long pending, long rejected,
                                   long cancelled, long failed, long refunded, Money approvedVolume) {
    }

    /** Contagens de faturas + soma dos valores não-canceladas. */
    public record InvoiceStats(long total, long pending, long paid, long overdue,
                               long cancelled, Money totalBilled) {
    }
}
