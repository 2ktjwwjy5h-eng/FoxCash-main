package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.infrastructure.outbound.persistence.entity.PlanDeliveryActionJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.PlanJpaEntity;

/**
 * Mapeia entre o modelo de domínio (planos + ações configuradas, Spec 49) e as
 * entidades JPA. O payload da ação é criptografado em repouso pela
 * {@code CryptoConverter} (o mapper passa o valor em claro para a entidade).
 */
public final class PlanMapper {

    private PlanMapper() {
    }

    public static PlanJpaEntity toEntity(Plan plan) {
        return new PlanJpaEntity(
                plan.id(),
                plan.storeId(),
                plan.name(),
                plan.description(),
                plan.price().amount(),
                plan.imageUrl(),
                plan.embedColor(),
                plan.featured(),
                plan.active(),
                plan.createdAt(),
                plan.updatedAt());
    }

    public static Plan toDomain(PlanJpaEntity entity) {
        return Plan.restore(
                entity.getId(),
                entity.getStoreId(),
                entity.getName(),
                entity.getDescription(),
                Money.of(entity.getPrice()),
                entity.getImageUrl(),
                entity.getEmbedColor(),
                entity.isFeatured(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public static PlanDeliveryActionJpaEntity toEntity(DeliveryAction action) {
        return new PlanDeliveryActionJpaEntity(
                action.id(),
                action.planId(),
                action.seq(),
                action.actionType(),
                action.payload(),
                action.createdAt());
    }

    public static DeliveryAction toDomain(PlanDeliveryActionJpaEntity entity) {
        return DeliveryAction.restore(
                entity.getId(),
                entity.getPlanId(),
                entity.getSeq(),
                entity.getActionType(),
                entity.getPayload(),
                entity.getCreatedAt());
    }
}
