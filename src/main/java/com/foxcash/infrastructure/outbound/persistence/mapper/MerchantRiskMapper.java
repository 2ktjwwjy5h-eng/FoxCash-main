package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.infrastructure.outbound.persistence.entity.MerchantRiskJpaEntity;

import java.util.UUID;

/**
 * Mapeia entre o snapshot de risco do dominio e a entidade JPA (Spec 16).
 */
public final class MerchantRiskMapper {

    private MerchantRiskMapper() {
    }

    public static MerchantRiskJpaEntity toEntity(MerchantRiskSnapshot snapshot) {
        return new MerchantRiskJpaEntity(
                UUID.randomUUID(),
                snapshot.merchantId(),
                snapshot.computedAt(),
                snapshot.approvedVolume().amount(),
                snapshot.totalTransactions(),
                snapshot.approvedTransactions(),
                snapshot.refundedTransactions(),
                snapshot.refundRatePercent(),
                snapshot.chargebackRatePercent(),
                snapshot.complaintsCount(),
                snapshot.refundAlert(),
                snapshot.chargebackAlert(),
                snapshot.complaintsAlert(),
                snapshot.riskScore());
    }

    public static MerchantRiskSnapshot toDomain(MerchantRiskJpaEntity entity) {
        return new MerchantRiskSnapshot(
                entity.getMerchantId(),
                entity.getComputedAt(),
                Money.of(entity.getApprovedVolume()),
                entity.getTotalTransactions(),
                entity.getApprovedTransactions(),
                entity.getRefundedTransactions(),
                entity.getRefundRatePercent(),
                entity.getChargebackRatePercent(),
                entity.getComplaintsCount(),
                entity.isRefundAlert(),
                entity.isChargebackAlert(),
                entity.isComplaintsAlert(),
                entity.getRiskScore());
    }
}
