package com.foxcash.domain.exception;

/**
 * Transição de estado inválida num broadcast (Spec 89) — o force-cancel do
 * master só é válido em {@code PENDING}; PROCESSING/COMPLETED/FAILED → 409
 * {@code invalid_broadcast_state}.
 */
public class InvalidBroadcastStateException extends RuntimeException {

    public InvalidBroadcastStateException() {
        super("Transicao de estado invalida para o broadcast");
    }
}
