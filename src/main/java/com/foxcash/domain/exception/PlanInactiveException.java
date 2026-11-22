package com.foxcash.domain.exception;

/**
 * Plano inativo (Spec 49) — a compra de plano desativado é recusada com 409
 * {@code plan_inactive} (o vendedor o tirou do ar).
 */
public class PlanInactiveException extends RuntimeException {

    public PlanInactiveException() {
        super("Plano inativo");
    }
}
