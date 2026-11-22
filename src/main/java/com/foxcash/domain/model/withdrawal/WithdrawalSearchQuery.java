package com.foxcash.domain.model.withdrawal;

import java.time.Instant;
import java.util.UUID;

/**
 * Consulta paginada de saidas (saques) com filtros opcionais — usada pelo
 * master ({@code GET /api/v1/master/withdrawals}, Spec 14) e futuramente
 * pelo dashboard do lojista.
 */
public record WithdrawalSearchQuery(
        UUID merchantId,
        WithdrawalStatus status,
        Instant from,
        Instant to,
        int page,
        int size) {

    public WithdrawalSearchQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
    }

    public static WithdrawalSearchQuery of(UUID merchantId, WithdrawalStatus status,
                                           Instant from, Instant to, int page, int size) {
        return new WithdrawalSearchQuery(merchantId, status, from, to, page, size);
    }
}
