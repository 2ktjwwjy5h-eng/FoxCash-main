package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Destino de saque inativo (Spec 32) — mapeado para 409
 * {@code destination_inactive}. O saque do lojista só aceita destinos ATIVOS
 * (Spec 31): um destino desativado pelo lojista não pode receber valores.
 */
public class DestinationInactiveException extends RuntimeException {

    public DestinationInactiveException(UUID destinationId) {
        super("Destination inactive: " + destinationId);
    }
}
