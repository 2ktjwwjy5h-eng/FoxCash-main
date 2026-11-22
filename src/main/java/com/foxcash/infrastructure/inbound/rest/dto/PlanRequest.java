package com.foxcash.infrastructure.inbound.rest.dto;

import java.math.BigDecimal;

/**
 * Request de criação/atualização de plano (Spec 49) — card visual. No
 * {@code PATCH} campos nulos = "não alterar"; no {@code POST} o {@code name} e
 * o {@code price} são obrigatórios (validação do domínio → 400
 * {@code invalid_argument}).
 */
public record PlanRequest(
        String name,
        BigDecimal price,
        String description,
        String imageUrl,
        String embedColor,
        Boolean featured) {
}
