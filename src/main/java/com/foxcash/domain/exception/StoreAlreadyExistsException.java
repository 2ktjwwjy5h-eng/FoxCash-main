package com.foxcash.domain.exception;

/**
 * O lojista já possui uma loja (Spec 48 — 1 loja por merchant nesta versão) —
 * mapeada para 409 {@code store_already_exists}.
 */
public class StoreAlreadyExistsException extends RuntimeException {

    public StoreAlreadyExistsException() {
        super("Loja ja cadastrada para este lojista");
    }
}
