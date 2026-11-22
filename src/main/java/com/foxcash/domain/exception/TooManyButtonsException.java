package com.foxcash.domain.exception;

/**
 * Limite de botões do menu excedido (Spec 50 — máx. 20 por loja) — mapeada
 * para 409 {@code too_many_buttons}.
 */
public class TooManyButtonsException extends RuntimeException {

    public TooManyButtonsException() {
        super("Limite de botoes do menu atingido (max. 20)");
    }
}
