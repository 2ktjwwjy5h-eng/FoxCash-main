package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.infrastructure.outbound.persistence.entity.WalletLedgerJpaEntity;

/**
 * Mapeia entre o modelo de domínio WalletLedgerEntry e a entidade JPA
 * WalletLedgerJpaEntity (Spec 27).
 */
public final class WalletLedgerMapper {

    private WalletLedgerMapper() {
    }

    public static WalletLedgerJpaEntity toEntity(WalletLedgerEntry entry) {
        return new WalletLedgerJpaEntity(
                entry.id(),
                entry.merchantId(),
                entry.type(),
                entry.amount().amount(),
                entry.balanceAfter().amount(),
                entry.referenceType(),
                entry.referenceId(),
                entry.createdAt());
    }

    public static WalletLedgerEntry toDomain(WalletLedgerJpaEntity entity) {
        return new WalletLedgerEntry(
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
