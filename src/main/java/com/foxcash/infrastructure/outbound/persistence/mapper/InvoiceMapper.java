package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.infrastructure.outbound.persistence.entity.InvoiceJpaEntity;

import java.time.YearMonth;

/**
 * Mapeia entre o modelo de dominio Invoice e a entidade JPA InvoiceJpaEntity.
 */
public final class InvoiceMapper {

    private InvoiceMapper() {
    }

    public static InvoiceJpaEntity toEntity(Invoice invoice) {
        return new InvoiceJpaEntity(
                invoice.id(),
                invoice.merchantId(),
                invoice.period().yearMonth().toString(),
                invoice.transactionCount(),
                invoice.transactionFeesTotal().amount(),
                invoice.monthlyFee().amount(),
                invoice.totalAmount().amount(),
                invoice.status(),
                invoice.dueDate(),
                invoice.createdAt(),
                invoice.paidAmount().amount(),
                invoice.collectedAt());
    }

    public static Invoice toDomain(InvoiceJpaEntity entity) {
        return Invoice.restore(
                entity.getId(),
                entity.getMerchantId(),
                BillingPeriod.of(YearMonth.parse(entity.getBillingPeriod())),
                entity.getTransactionCount(),
                Money.of(entity.getTransactionFeesTotal()),
                Money.of(entity.getMonthlyFee()),
                Money.of(entity.getTotalAmount()),
                entity.getDueDate(),
                entity.getStatus(),
                entity.getCreatedAt(),
                Money.of(entity.getPaidAmount()),
                entity.getCollectedAt());
    }
}
