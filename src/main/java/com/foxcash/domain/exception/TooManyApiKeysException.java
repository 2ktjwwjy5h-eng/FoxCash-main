package com.foxcash.domain.exception;

/**
 * Limite de chaves ativas do lojista excedido (Spec 24) — mapeada para 409
 * {@code too_many_keys}.
 */
public class TooManyApiKeysException extends RuntimeException {

    public TooManyApiKeysException(int maxActive) {
        super("Limite de " + maxActive + " chaves ativas por lojista atingido");
    }
}
