package com.foxcash.domain.port.in;

import com.foxcash.domain.model.delivery.DeliveryActionStatus;
import com.foxcash.domain.model.delivery.PlanDelivery;

import java.util.UUID;

/**
 * Porta de entrada: execução das ações de entrega (Spec 49) — o bot confirma
 * cada ação individualmente (DONE/FAILED); quando todas DONE a entrega vira
 * DELIVERED (automático); qualquer FAILED → entrega FAILED (retry reabre).
 * Tudo por {@code merchantId} do principal (404 cross-loja); replay idempotente.
 */
public interface DeliveryActionCompletionUseCase {

    /** Completa a ação {@code seq} da entrega (gate: só PENDING; replay 204). */
    void complete(UUID merchantId, UUID deliveryId, int seq, DeliveryActionStatus status, String note);

    /** Reabre as ações FAILED como PENDING e a entrega volta a PENDING (idempotente). */
    PlanDelivery retry(UUID merchantId, UUID deliveryId);
}
