package com.foxcash.domain.exception;

/**
 * Token Turnstile (Cloudflare) ausente ou invalido (Spec 63, Fase C) —
 * mapeada para 403 {@code turnstile_failed} no GlobalExceptionHandler.
 */
public class TurnstileFailedException extends RuntimeException {

    public TurnstileFailedException() {
        super("Falha na verificacao anti-bot (Turnstile)");
    }
}
