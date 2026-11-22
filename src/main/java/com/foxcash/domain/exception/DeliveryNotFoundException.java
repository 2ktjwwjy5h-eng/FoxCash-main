package com.foxcash.domain.exception;

/**
 * Entrega de plano inexistente na loja (Spec 49) — mapeada para 404
 * {@code delivery_not_found} (404 cross-loja sem revelar existência).
 */
public class DeliveryNotFoundException extends RuntimeException {

    public DeliveryNotFoundException() {
        super("Entrega nao encontrada");
    }
}
