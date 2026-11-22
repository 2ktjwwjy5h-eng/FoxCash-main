package com.foxcash.domain.exception;

/**
 * Botão do menu inexistente na loja (Spec 50) — mapeada para 404
 * {@code button_not_found}.
 */
public class StoreButtonNotFoundException extends RuntimeException {

    public StoreButtonNotFoundException() {
        super("Botao do menu nao encontrado");
    }
}
