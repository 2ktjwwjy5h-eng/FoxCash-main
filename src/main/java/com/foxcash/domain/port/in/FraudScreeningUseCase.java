package com.foxcash.domain.port.in;

/**
 * Triagem de antifraude em tempo real (Spec 19), executada antes de criar uma
 * cobranca. Lanca {@link com.foxcash.domain.exception.FraudRuleViolationException}
 * se a unica regra dura for violada (comprador com historico de chargeback).
 */
public interface FraudScreeningUseCase {

    /**
     * @param buyerIdHash hash SHA-256 do buyerId opaco (null se o bot nao enviou)
     */
    void screen(String buyerIdHash);
}
