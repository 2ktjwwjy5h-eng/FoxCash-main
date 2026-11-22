package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Saques bloqueados para o lojista pelo master (Spec 14). O bloqueio impede
 * novos saques (nao estorna os ja efetivados). Mapeado para 409
 * {@code withdrawals_blocked}.
 */
public class WithdrawalsBlockedException extends RuntimeException {

    public WithdrawalsBlockedException(UUID merchantId) {
        super("Withdrawals are blocked for merchant " + merchantId);
    }
}
