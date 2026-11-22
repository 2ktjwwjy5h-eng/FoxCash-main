package com.foxcash.domain.exception;

/**
 * Transição de estado inválida no ciclo de vida do bot de venda (Spec 74) —
 * 409 {@code invalid_bot_state}. Ex.: pausar um bot em {@code REGISTERING},
 * ativar um bot removido.
 */
public class InvalidBotStateException extends RuntimeException {

    public InvalidBotStateException(String message) {
        super(message);
    }
}
