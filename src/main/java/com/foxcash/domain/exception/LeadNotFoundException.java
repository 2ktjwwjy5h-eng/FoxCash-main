package com.foxcash.domain.exception;

/**
 * Lead não encontrado no escopo do lojista (Spec 83 C3) — 404
 * {@code lead_not_found} cross-lojista sem revelar existência.
 */
public class LeadNotFoundException extends RuntimeException {

    public LeadNotFoundException(String message) {
        super(message);
    }
}
