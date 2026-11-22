package com.foxcash.domain.exception;

/**
 * Loja inexistente (Spec 48) — mapeada para 404 {@code store_not_found}.
 */
public class StoreNotFoundException extends RuntimeException {

    public StoreNotFoundException() {
        super("Loja nao encontrada");
    }
}
