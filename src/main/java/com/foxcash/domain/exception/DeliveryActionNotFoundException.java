package com.foxcash.domain.exception;

/**
 * Ação de entrega não encontrada na entrega (Spec 49) — mapeada para 404
 * {@code delivery_action_not_found}.
 */
public class DeliveryActionNotFoundException extends RuntimeException {

    public DeliveryActionNotFoundException() {
        super("Acao de entrega nao encontrada");
    }
}
