package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.admin.DashboardSummary;

import java.math.BigDecimal;

/**
 * Response do resumo agregado do dashboard administrativo.
 */
public record DashboardSummaryResponse(
        MerchantStats merchants,
        TransactionStats transactions,
        InvoiceStats invoices) {

    public record MerchantStats(long total, long active, long suspended, long inactive) {
    }

    public record TransactionStats(long total, long approved, long pending, long rejected,
                                   long cancelled, long failed, long refunded, BigDecimal approvedVolume) {
    }

    public record InvoiceStats(long total, long pending, long paid, long overdue,
                               long cancelled, BigDecimal totalBilled) {
    }

    public static DashboardSummaryResponse from(DashboardSummary summary) {
        return new DashboardSummaryResponse(
                new MerchantStats(
                        summary.merchants().total(),
                        summary.merchants().active(),
                        summary.merchants().suspended(),
                        summary.merchants().inactive()),
                new TransactionStats(
                        summary.transactions().total(),
                        summary.transactions().approved(),
                        summary.transactions().pending(),
                        summary.transactions().rejected(),
                        summary.transactions().cancelled(),
                        summary.transactions().failed(),
                        summary.transactions().refunded(),
                        summary.transactions().approvedVolume().amount()),
                new InvoiceStats(
                        summary.invoices().total(),
                        summary.invoices().pending(),
                        summary.invoices().paid(),
                        summary.invoices().overdue(),
                        summary.invoices().cancelled(),
                        summary.invoices().totalBilled().amount()));
    }
}
