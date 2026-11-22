package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.infrastructure.outbound.persistence.entity.WithdrawalJpaEntity;

/**
 * Mapeia entre o modelo de dominio Withdrawal e a entidade JPA
 * WithdrawalJpaEntity (Spec 14, Spec 32: destino/tipo/reason/snapshot;
 * Spec 36: external_reference/payout_provider/falha).
 */
public final class WithdrawalMapper {

    private WithdrawalMapper() {
    }

    public static WithdrawalJpaEntity toEntity(Withdrawal withdrawal) {
        return new WithdrawalJpaEntity(
                withdrawal.id(),
                withdrawal.merchantId(),
                withdrawal.amount().amount(),
                withdrawal.fee().amount(),
                withdrawal.status(),
                withdrawal.destinationId(),
                withdrawal.type(),
                withdrawal.reason(),
                withdrawal.destinationKeySnapshot(),
                withdrawal.createdAt(),
                withdrawal.completedAt(),
                withdrawal.rejectedAt(),
                withdrawal.externalReference(),
                withdrawal.payoutProvider(),
                withdrawal.failureReason(),
                withdrawal.failedAt());
    }

    public static Withdrawal toDomain(WithdrawalJpaEntity entity) {
        return Withdrawal.restore(
                entity.getId(),
                entity.getMerchantId(),
                Money.of(entity.getAmount()),
                Money.of(entity.getFeeAmount()),
                entity.getStatus(),
                entity.getDestinationId(),
                entity.getType(),
                entity.getReason(),
                entity.getDestinationKeySnapshot(),
                entity.getCreatedAt(),
                entity.getCompletedAt(),
                entity.getRejectedAt(),
                entity.getExternalReference(),
                entity.getPayoutProvider(),
                entity.getFailureReason(),
                entity.getFailedAt());
    }
}
