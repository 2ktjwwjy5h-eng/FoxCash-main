package com.foxcash.domain.exception;

/**
 * Limite de cobranças PENDENTES do lojista excedido (Spec 58 — cap por conta
 * para degradação controlada: 1 lojista não pode acumular cobranças ilimitadas,
 * gerando custo PSP + ledger) — mapeada para 409 {@code too_many_pending_charges}.
 */
public class TooManyPendingChargesException extends RuntimeException {

    public TooManyPendingChargesException(int maxPending) {
        super("Limite de cobrancas pendentes atingido (max. " + maxPending + ")");
    }
}
