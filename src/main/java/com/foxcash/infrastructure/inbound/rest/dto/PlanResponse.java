package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.port.in.PlanManagementUseCase.PlanView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Resposta do plano (Spec 49) — card visual. Na listagem o payload das ações
 * NUNCA sai: apenas {@code hasDeliveryActions} (o payload criptografado em
 * repouso é devolvido em claro apenas na API de ações do próprio lojista).
 */
public record PlanResponse(
        UUID id,
        String name,
        BigDecimal price,
        String description,
        String imageUrl,
        String embedColor,
        boolean featured,
        boolean active,
        boolean hasDeliveryActions,
        Instant createdAt,
        Instant updatedAt) {

    public static PlanResponse from(Plan plan) {
        return new PlanResponse(
                plan.id(), plan.name(), plan.price().amount(), plan.description(),
                plan.imageUrl(), plan.embedColor(), plan.featured(), plan.active(),
                false, plan.createdAt(), plan.updatedAt());
    }

    public static PlanResponse from(PlanView view) {
        return new PlanResponse(
                view.plan().id(), view.plan().name(), view.plan().price().amount(),
                view.plan().description(), view.plan().imageUrl(), view.plan().embedColor(),
                view.plan().featured(), view.plan().active(), view.hasDeliveryActions(),
                view.plan().createdAt(), view.plan().updatedAt());
    }
}
