package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.infrastructure.outbound.persistence.entity.ReceivableJpaEntity;

/**
 * Mapeia entre o modelo de dominio Receivable e a entidade JPA
 * ReceivableJpaEntity (Spec 33).
 */
public final class ReceivableMapper {

    private ReceivableMapper() {
    }

    public static ReceivableJpaEntity toEntity(Receivable receivable) {
        return new ReceivableJpaEntity(
                receivable.id(),
                receivable.merchantId(),
                receivable.origin(),
                receivable.referenceId(),
                receivable.amount().amount(),
                receivable.paidAmount().amount(),
                receivable.status(),
                receivable.createdAt(),
                receivable.paidAt(),
                receivable.updatedAt());
    }

    public static Receivable toDomain(ReceivableJpaEntity entity) {
        return Receivable.restore(
                entity.getId(),
                entity.getMerchantId(),
                entity.getOrigin(),
                entity.getReferenceId(),
                Money.of(entity.getAmount()),
                Money.of(entity.getPaidAmount()),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getPaidAt(),
                entity.getUpdatedAt());
    }
}
