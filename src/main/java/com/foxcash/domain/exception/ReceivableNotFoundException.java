package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Receivable inexistente (Spec 34 — write-off do master). Mapeado para 404
 * {@code receivable_not_found}.
 */
public class ReceivableNotFoundException extends RuntimeException {

    public ReceivableNotFoundException(UUID receivableId) {
        super("Receivable " + receivableId + " not found");
    }
}
