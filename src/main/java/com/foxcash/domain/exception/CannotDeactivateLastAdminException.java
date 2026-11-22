package com.foxcash.domain.exception;

/**
 * Tentativa de desativar o último admin ativo (Spec 41) → 409
 * {@code cannot_deactivate_last_admin} — a plataforma nunca fica sem admin.
 */
public class CannotDeactivateLastAdminException extends RuntimeException {

    public CannotDeactivateLastAdminException() {
        super("Nao e possivel desativar o ultimo admin (can_manage_masters) ativo");
    }
}
