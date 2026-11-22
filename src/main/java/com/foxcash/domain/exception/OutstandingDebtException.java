package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Saque bloqueado por inadimplencia (Spec 33): o lojista possui receivable
 * {@code OPEN}/{@code PARTIAL} (divida formal com a plataforma). Mapeado para
 * 409 {@code outstanding_debt}. O saque administrativo do master NAO valida
 * este bloqueio (o master recolhe justamente o devedor).
 */
public class OutstandingDebtException extends RuntimeException {

    public OutstandingDebtException(UUID merchantId) {
        super("Merchant " + merchantId + " has outstanding debt — withdrawals are blocked");
    }
}
