package com.foxcash.domain.port.out;

import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia de faturas.
 */
public interface InvoiceRepository {

    Invoice save(Invoice invoice);

    Optional<Invoice> findById(UUID invoiceId);

    Optional<Invoice> findByMerchantIdAndPeriod(UUID merchantId, BillingPeriod period);

    /**
     * Lista as faturas do lojista (mais recentes primeiro), limitada a
     * {@code limit} — auditoria 2026-08-05: nunca carrega o historico inteiro.
     */
    List<Invoice> findByMerchantId(UUID merchantId, int limit);

    /**
     * Faturas nao quitadas e vencidas em {@code dueDate} (Spec 33): status
     * {@code PENDING} ou {@code OVERDUE} com saldo devedor ({@code totalAmount >
     * paidAmount}). O job de cobranca re-cobra {@code OVERDUE} quando o lojista
     * volta a ter saldo — fecha o ciclo da divida formal.
     */
    List<Invoice> findUnpaidDueOnOrBefore(LocalDate dueDate);
}
