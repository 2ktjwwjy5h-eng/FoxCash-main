package com.foxcash.domain.exception;

/**
 * Username de perfil master já cadastrado (Spec 41) → 409 {@code master_already_exists}.
 */
public class MasterAlreadyExistsException extends RuntimeException {

    public MasterAlreadyExistsException() {
        super("Ja existe um perfil master com este username");
    }
}
