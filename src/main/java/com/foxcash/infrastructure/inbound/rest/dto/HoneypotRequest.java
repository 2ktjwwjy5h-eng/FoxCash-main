package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Marca/desmarca o lojista como honeypot (Spec 98 — D4.5): ambiente controlado
 * de monitoramento antifraude — a conta continua operando no provedor trap (sem
 * crédito real), saques bloqueados. Reversível.
 */
public record HoneypotRequest(boolean enabled) {
}
