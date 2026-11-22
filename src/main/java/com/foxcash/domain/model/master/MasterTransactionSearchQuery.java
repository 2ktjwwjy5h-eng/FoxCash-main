package com.foxcash.domain.model.master;

import com.foxcash.domain.model.transaction.TransactionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Consulta paginada de transacoes do dashboard master (Spec 15) com filtros
 * opcionais — mesmo padrao das consultas do dashboard/admin (Spec 8).
 */
public record MasterTransactionSearchQuery(
        UUID merchantId,
        TransactionStatus status,
        Instant from,
        Instant to,
        int page,
        int size) {

    public MasterTransactionSearchQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
    }
}
