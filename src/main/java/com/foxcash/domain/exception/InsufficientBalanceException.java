package com.foxcash.domain.exception;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Lojista nao possui saldo suficiente para o saque solicitado (Spec 14).
 * Mapeado para 409 {@code insufficient_balance}.
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(UUID merchantId, Money requested) {
        super("Saldo insuficiente para realizar o saque.");
    }
}
