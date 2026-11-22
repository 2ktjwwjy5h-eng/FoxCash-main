package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.delivery.DeliveryActionExec;
import com.foxcash.domain.model.delivery.PlanDelivery;
import com.foxcash.infrastructure.outbound.persistence.entity.PlanDeliveryActionExecJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.PlanDeliveryJpaEntity;

/**
 * Mapeia entre o modelo de domínio (entregas + execução de ações, Spec 49) e
 * as entidades JPA. O destinatário ({@code platform_user_id}) e o snapshot do
 * payload são criptografados em repouso pela {@code CryptoConverter}.
 */
public final class PlanDeliveryMapper {

    private PlanDeliveryMapper() {
    }

    public static PlanDeliveryJpaEntity toEntity(PlanDelivery delivery) {
        return new PlanDeliveryJpaEntity(
                delivery.id(),
                delivery.storeId(),
                delivery.planId(),
                delivery.transactionId(),
                delivery.platform(),
                delivery.platformUserId(),
                delivery.buyerIdHash(),
                delivery.status(),
                delivery.createdAt(),
                delivery.deliveredAt(),
                delivery.termsAcceptedAt(),
                delivery.buyerIp(),
                delivery.buyerDevice());
    }

    public static PlanDelivery toDomain(PlanDeliveryJpaEntity entity) {
        return PlanDelivery.restore(
                entity.getId(),
                entity.getStoreId(),
                entity.getPlanId(),
                entity.getTransactionId(),
                entity.getPlatform(),
                entity.getPlatformUserId(),
                entity.getBuyerIdHash(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getDeliveredAt(),
                entity.getTermsAcceptedAt(),
                entity.getBuyerIp(),
                entity.getBuyerDevice());
    }

    public static PlanDeliveryActionExecJpaEntity toEntity(DeliveryActionExec exec) {
        return new PlanDeliveryActionExecJpaEntity(
                exec.id(),
                exec.deliveryId(),
                exec.seq(),
                exec.actionType(),
                exec.payloadSnapshot(),
                exec.status(),
                exec.note(),
                exec.executedAt());
    }

    public static DeliveryActionExec toDomain(PlanDeliveryActionExecJpaEntity entity) {
        return DeliveryActionExec.restore(
                entity.getId(),
                entity.getDeliveryId(),
                entity.getSeq(),
                entity.getActionType(),
                entity.getPayloadSnapshot(),
                entity.getStatus(),
                entity.getNote(),
                entity.getExecutedAt());
    }
}
