package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.infrastructure.outbound.persistence.entity.WithdrawalDestinationJpaEntity;

/**
 * Mapeia entre o modelo de domínio WithdrawalDestination e a entidade JPA
 * WithdrawalDestinationJpaEntity (Spec 31). {@code status} (ACTIVE/INACTIVE) do
 * banco vira {@code boolean active} no domínio.
 */
public final class WithdrawalDestinationMapper {

    private static final String ACTIVE = "ACTIVE";

    private WithdrawalDestinationMapper() {
    }

    public static WithdrawalDestinationJpaEntity toEntity(WithdrawalDestination d) {
        return new WithdrawalDestinationJpaEntity(
                d.id(), d.merchantId(), d.type(), d.value(), d.bankCode(), d.agency(),
                d.account(), d.accountType(), d.label(),
                d.active() ? ACTIVE : "INACTIVE",
                d.createdAt(), d.updatedAt());
    }

    public static WithdrawalDestination toDomain(WithdrawalDestinationJpaEntity e) {
        return WithdrawalDestination.restore(
                e.getId(), e.getMerchantId(), e.getType(), e.getValue(), e.getBankCode(),
                e.getAgency(), e.getAccount(), e.getAccountType(), e.getLabel(),
                ACTIVE.equals(e.getStatus()), e.getCreatedAt(), e.getUpdatedAt());
    }
}
