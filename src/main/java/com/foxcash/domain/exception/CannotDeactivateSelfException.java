package com.foxcash.domain.exception;

/**
 * Tentativa de desativar o próprio perfil master (Spec 41) → 409
 * {@code cannot_deactivate_self}.
 */
public class CannotDeactivateSelfException extends RuntimeException {

    public CannotDeactivateSelfException() {
        super("Voce nao pode desativar o proprio perfil master");
    }
}
