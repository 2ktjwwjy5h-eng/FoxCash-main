package com.foxcash.domain.exception;

/**
 * Regra de antifraude violada na triagem em tempo real (Spec 19) — mapeada
 * para HTTP 409 com o codigo especifico da regra (ex.: {@code fraud_buyer_blocked}).
 */
public class FraudRuleViolationException extends RuntimeException {

    private final String code;

    public FraudRuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Codigo canonico da regra violada (usado no corpo {@code ErrorResponse}). */
    public String code() {
        return code;
    }
}
