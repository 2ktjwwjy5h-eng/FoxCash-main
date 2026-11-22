package com.foxcash.domain.exception;

/**
 * Conflito de idempotência (Spec 28): a {@code idempotencyKey} já produziu uma
 * cobrança que falhou no PSP ({@code FAILED}) — o retry com a mesma chave
 * responde 409 {@code idempotency_conflict}; o cliente deve trocar de chave.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotencyKey '" + idempotencyKey + "' ja produziu uma cobranca — troque a chave");
    }
}
