package com.foxcash.domain.exception;

/**
 * Limite de destinos de saque ATIVOS do lojista excedido (Spec 31) — mapeada
 * para 409 {@code too_many_destinations}.
 */
public class TooManyDestinationsException extends RuntimeException {

    public TooManyDestinationsException(int maxActive) {
        super("Limite de " + maxActive + " destinos de saque ativos por lojista atingido");
    }
}
