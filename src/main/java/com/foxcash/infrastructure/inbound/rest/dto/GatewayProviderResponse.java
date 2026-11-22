package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.transaction.ProviderVolume;

import java.math.BigDecimal;

/**
 * Resposta da visão de um adquirente no portal (Spec 107): nome amigável,
 * ordem de fallback, estado do circuito e extrato do período. Nunca expõe
 * segredos/credenciais do adquirente.
 *
 * @param provider       id interno (ex.: "pagarme", "asaas", "mock").
 * @param displayName    nome amigável (ex.: "Pagar.me").
 * @param order          1 = principal, 2/3 = fallback 1/2 (da policy do lojista).
 * @param circuitState   CLOSED / OPEN / HALF_OPEN (Spec 91).
 * @param volumePeriod   Σ aprovado no período (extrato).
 * @param countPeriod    COUNT aprovado no período (extrato).
 */
public record GatewayProviderResponse(
        String provider,
        String displayName,
        int order,
        String circuitState,
        BigDecimal volumePeriod,
        long countPeriod) {

    /** Monta a partir do provider + extrato do período (Spec 107). */
    public static GatewayProviderResponse from(String provider, String displayName, int order,
                                               String circuitState, ProviderVolume volume) {
        return new GatewayProviderResponse(
                provider,
                displayName,
                order,
                circuitState,
                volume != null ? volume.volume() : BigDecimal.ZERO,
                volume != null ? volume.count() : 0L);
    }
}
