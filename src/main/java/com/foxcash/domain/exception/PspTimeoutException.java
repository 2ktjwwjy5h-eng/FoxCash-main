package com.foxcash.domain.exception;

/**
 * Timeout no PSP ao criar a cobrança (Spec 28): a resposta é INCERTA — a
 * cobrança PODE ter sido criada no provedor. Responde 502 {@code psp_timeout};
 * a transação fica {@code PENDING} (sem QR) e o retry com a mesma chave conclui.
 */
public class PspTimeoutException extends RuntimeException {

    public PspTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
