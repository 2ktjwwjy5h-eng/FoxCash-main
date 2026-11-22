package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.fraud.Chargeback;
import com.foxcash.infrastructure.outbound.persistence.entity.ChargebackJpaEntity;

/**
 * Mapeia entre o modelo de dominio Chargeback e a entidade JPA (Spec 19).
 */
public final class ChargebackMapper {

    private ChargebackMapper() {
    }

    public static ChargebackJpaEntity toEntity(Chargeback chargeback) {
        return new ChargebackJpaEntity(
                chargeback.id(),
                chargeback.merchantId(),
                chargeback.transactionId(),
                chargeback.buyerIdHash(),
                chargeback.amount().amount(),
                chargeback.reason(),
                chargeback.createdAt());
    }

    public static Chargeback toDomain(ChargebackJpaEntity entity) {
        return new Chargeback(
                entity.getId(),
                entity.getMerchantId(),
                entity.getTransactionId(),
                entity.getBuyerIdHash(),
                Money.of(entity.getAmount()),
                entity.getReason(),
                entity.getCreatedAt());
    }
}
