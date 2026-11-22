package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Chargeback duplicado para a mesma transação (Spec 29): o débito do estorno
 * acontece UMA única vez (fonte única). Registro duplicado → 409
 * {@code chargeback_already_exists} (antes: 500 por violação de unicidade).
 */
public class ChargebackAlreadyExistsException extends RuntimeException {

    public ChargebackAlreadyExistsException(UUID transactionId) {
        super("Chargeback ja registrado para a transacao " + transactionId);
    }
}
