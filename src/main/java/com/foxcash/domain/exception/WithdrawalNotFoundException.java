package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Saida (saque) nao encontrada — mapeado para 404 {@code withdrawal_not_found}.
 */
public class WithdrawalNotFoundException extends RuntimeException {

    public WithdrawalNotFoundException(UUID withdrawalId) {
        super("Withdrawal not found: " + withdrawalId);
    }

    public WithdrawalNotFoundException(String externalReference) {
        super("Withdrawal not found for external reference: " + externalReference);
    }
}
