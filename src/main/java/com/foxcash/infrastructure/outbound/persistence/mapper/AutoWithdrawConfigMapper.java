package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.withdrawal.AutoWithdrawConfig;
import com.foxcash.infrastructure.outbound.persistence.entity.AutoWithdrawConfigJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link AutoWithdrawConfig} e a entidade JPA
 * {@link AutoWithdrawConfigJpaEntity} (Spec 70). {@code threshold_amount}
 * (NUMERIC(15,2)) vira {@code Money} no domínio.
 */
public final class AutoWithdrawConfigMapper {

    private AutoWithdrawConfigMapper() {
    }

    public static AutoWithdrawConfigJpaEntity toEntity(AutoWithdrawConfig c) {
        return new AutoWithdrawConfigJpaEntity(
                c.id(), c.merchantId(), c.enabled(), c.threshold().amount(),
                c.destinationId(), c.createdAt(), c.updatedAt());
    }

    public static AutoWithdrawConfig toDomain(AutoWithdrawConfigJpaEntity e) {
        return AutoWithdrawConfig.restore(
                e.getId(), e.getMerchantId(), e.isEnabled(),
                Money.of(e.getThresholdAmount()), e.getDestinationId(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
