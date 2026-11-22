package com.foxcash.domain.model.delivery;

/**
 * Status de execução de uma ação de entrega (Spec 49).
 * <p>PENDING → DONE | FAILED (terminal; retry reabre FAILED → PENDING).
 */
public enum DeliveryActionStatus {
    PENDING,
    DONE,
    FAILED
}
