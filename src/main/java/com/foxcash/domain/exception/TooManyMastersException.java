package com.foxcash.domain.exception;

/**
 * Limite de perfis master atingido (Spec 41 — {@code foxcash.master.max-profiles},
 * default 4) → 409 {@code too_many_masters}.
 */
public class TooManyMastersException extends RuntimeException {

    public TooManyMastersException(int maxProfiles) {
        super("Limite de perfis master atingido (maximo de " + maxProfiles + ")");
    }
}
