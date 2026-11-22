package com.foxcash.domain.port.in;

import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.DeliveryActionType;
import com.foxcash.domain.model.plan.Plan;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: gestão de planos e ações de entrega pelo portal (Spec 49),
 * {@code ROLE_MERCHANT_PORTAL}. Tudo por {@code merchantId} do principal — a
 * loja (1:1) é resolvida internamente; nunca por id solto (404 cross-loja). O
 * payload das ações é validado por tipo e criptografado em repouso; a listagem
 * nunca devolve o payload (só {@code hasDeliveryActions}).
 */
public interface PlanManagementUseCase {

    /** Planos da loja com {@code hasDeliveryActions} (listagem sem payload). */
    List<PlanView> list(UUID merchantId);

    /** 404 {@code plan_not_found} se não existe na loja do lojista. */
    Plan get(UUID merchantId, UUID planId);

    Plan create(UUID merchantId, SavePlanCommand command);

    Plan update(UUID merchantId, UUID planId, SavePlanCommand command);

    void setActive(UUID merchantId, UUID planId, boolean active);

    /** Ações do plano em ordem de {@code seq}. */
    List<DeliveryAction> listActions(UUID merchantId, UUID planId);

    DeliveryAction addAction(UUID merchantId, UUID planId, SaveActionCommand command);

    /** A rota é por actionId (o plano é resolvido pela ação — 404 cross-loja). */
    DeliveryAction updateAction(UUID merchantId, UUID actionId, SaveActionCommand command);

    /** 204 (rota por actionId — o plano é resolvido pela ação). */
    void deleteAction(UUID merchantId, UUID actionId);

    /** Campos nulos = não alterar no update. */
    record SavePlanCommand(String name, BigDecimal price, String description,
                           String imageUrl, String embedColor, Boolean featured) {
    }

    /** Ação de entrega: tipo + payload JSON (validado por tipo). */
    record SaveActionCommand(int seq, DeliveryActionType actionType, String payloadJson) {
    }

    /** Plano + flag de ações (listagem — o payload nunca sai na listagem). */
    record PlanView(Plan plan, boolean hasDeliveryActions) {
    }
}
