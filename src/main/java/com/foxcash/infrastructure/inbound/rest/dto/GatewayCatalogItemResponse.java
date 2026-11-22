package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Item do catálogo de gateways disponíveis (Spec 107): integrações
 * Pix/Cartão/Cripto com disponibilidade e configuração (read-only no portal).
 *
 * @param id         id estável (ex.: "pagarme", "cielo", "bitpay").
 * @param name       nome amigável (ex.: "Pagar.me").
 * @param category   PIX / CARTÃO / CRIPTO.
 * @param available  true = tem adapter real hoje (Spec 91).
 * @param configured true = presente no roteamento configurado.
 */
public record GatewayCatalogItemResponse(
        String id,
        String name,
        String category,
        boolean available,
        boolean configured) {
}
