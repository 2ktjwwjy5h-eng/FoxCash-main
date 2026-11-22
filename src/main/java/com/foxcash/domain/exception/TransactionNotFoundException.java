package com.foxcash.domain.exception;

/**
 * Transacao Pix nao encontrada (por id ou por referencia externa).
 */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(java.util.UUID transactionId) {
        super("Transaction not found: " + transactionId);
    }

    public TransactionNotFoundException(String externalReference) {
        super("Transaction not found for externalReference: " + externalReference);
    }
}
