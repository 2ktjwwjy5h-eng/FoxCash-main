package com.foxcash.domain.model.transaction;

import java.math.BigDecimal;

/**
 * Extrato de um adquirente (provider) no período (Spec 107): volume (Σ) e
 * contagem de transações APPROVED. Imutável — usado na visão de gateways do
 * portal (leitura somente).
 */
public record ProviderVolume(String provider, BigDecimal volume, long count) {
}
