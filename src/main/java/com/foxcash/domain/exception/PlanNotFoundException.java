package com.foxcash.domain.exception;

/**
 * Plano inexistente na loja (Spec 49) — mapeada para 404 {@code plan_not_found}.
 */
public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException() {
        super("Plano nao encontrado");
    }
}
