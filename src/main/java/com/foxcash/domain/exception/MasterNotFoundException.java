package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Perfil master inexistente (Spec 41) → 404 {@code master_not_found}.
 */
public class MasterNotFoundException extends RuntimeException {

    public MasterNotFoundException(UUID masterId) {
        super("Perfil master nao encontrado: " + masterId);
    }
}
