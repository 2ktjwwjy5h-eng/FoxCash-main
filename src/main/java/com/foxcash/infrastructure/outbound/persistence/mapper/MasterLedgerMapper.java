package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.infrastructure.outbound.persistence.entity.MasterLedgerJpaEntity;

/**
 * Mapeia entre o modelo de dominio MasterLedgerEntry e a entidade JPA
 * MasterLedgerJpaEntity (Spec 34).
 */
public final class MasterLedgerMapper {

    private MasterLedgerMapper() {
    }

    public static MasterLedgerJpaEntity toEntity(MasterLedgerEntry entry) {
        return new MasterLedgerJpaEntity(
                entry.id(),
                entry.merchantId(),
                entry.type(),
                entry.amount().amount(),
                entry.balanceAfter().amount(),
                entry.referenceType(),
                entry.referenceId(),
                entry.createdAt());
    }

    public static MasterLedgerEntry toDomain(MasterLedgerJpaEntity entity) {
        return new MasterLedgerEntry(
                entity.getId(),
                entity.getMerchantId(),
                entity.getEntryType(),
                Money.of(entity.getAmount()),
                Money.of(entity.getBalanceAfter()),
                entity.getReferenceType(),
                entity.getReferenceId(),
                entity.getCreatedAt());
    }
}
