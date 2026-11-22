package com.foxcash.domain.model.master;

import com.foxcash.domain.model.transaction.TransactionStatus;

import java.time.Instant;

/**
 * Filtros/paginacao das transacoes do lojista no painel do master (Spec 97,
 * D3.3) — mesmo shape do {@code MasterTransactionSearchQuery} (Spec 15), mas
 * escopado por lojista (o path ja carrega o {@code merchantId}).
 */
public record MerchantPanelTransactionQuery(
        TransactionStatus status,
        Instant from,
        Instant to,
        int page,
        int size) {
}
