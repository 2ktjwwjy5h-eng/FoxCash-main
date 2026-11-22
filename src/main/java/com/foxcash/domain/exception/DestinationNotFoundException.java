package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Destino de saque não encontrado para o lojista — mapeado para 404
 * {@code destination_not_found}. Usado quando o destino não existe OU pertence
 * a outro lojista (não revela existência — privacidade da Spec 31).
 */
public class DestinationNotFoundException extends RuntimeException {

    public DestinationNotFoundException(UUID destinationId) {
        super("Destination not found: " + destinationId);
    }
}
