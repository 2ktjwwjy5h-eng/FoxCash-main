package com.foxcash.domain.model.delivery;

/**
 * Status da entrega do plano (Spec 49).
 * <p>PENDING → DELIVERED (todas as ações DONE / plano sem ações) | FAILED
 * (qualquer ação FAILED). FAILED → PENDING via retry (reabre ações FAILED).
 */
public enum PlanDeliveryStatus {
    PENDING,
    DELIVERED,
    FAILED
}
