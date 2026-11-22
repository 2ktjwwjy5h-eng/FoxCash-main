package com.foxcash.domain.model.risk;

import java.util.UUID;

/**
 * Consulta paginada de snapshots de risco (Spec 16) — filtro opcional por
 * lojista, mesmo padrao das consultas do dashboard (Spec 8).
 */
public record RiskSearchQuery(UUID merchantId, int page, int size) {

    public RiskSearchQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
    }
}
