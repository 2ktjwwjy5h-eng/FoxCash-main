package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.billing.InvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response de fatura.
 */
public record InvoiceResponse(
        UUID id,
        UUID merchantId,
        String billingPeriod,
        int transactionCount,
        BigDecimal transactionFeesTotal,
        BigDecimal monthlyFee,
        BigDecimal totalAmount,
        InvoiceStatus status,
        LocalDate dueDate,
        Instant createdAt) {

    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(
                invoice.id(),
                invoice.merchantId(),
                invoice.period().yearMonth().toString(),
                invoice.transactionCount(),
                invoice.transactionFeesTotal().amount(),
                invoice.monthlyFee().amount(),
                invoice.totalAmount().amount(),
                invoice.status(),
                invoice.dueDate(),
                invoice.createdAt());
    }
}
