package com.foxcash.domain.model.billing;

import java.time.Instant;
import java.util.UUID;

/**
 * Consulta paginada de receivables com filtros opcionais — usada pelo master
 * ({@code GET /api/v1/master/receivables} e
 * {@code GET /api/v1/master/merchants/{id}/receivables}, Spec 33).
 */
public record ReceivableSearchQuery(
        UUID merchantId,
        ReceivableStatus status,
        ReceivableOrigin origin,
        Instant from,
        Instant to,
        int page,
        int size) {

    public ReceivableSearchQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
    }

    public static ReceivableSearchQuery of(UUID merchantId, ReceivableStatus status,
                                           ReceivableOrigin origin, Instant from, Instant to,
                                           int page, int size) {
        return new ReceivableSearchQuery(merchantId, status, origin, from, to, page, size);
    }
}
