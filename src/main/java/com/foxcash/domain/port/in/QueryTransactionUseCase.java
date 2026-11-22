package com.foxcash.domain.port.in;

import com.foxcash.domain.model.transaction.PixTransaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de entrada: consultas de transacoes Pix.
 */
public interface QueryTransactionUseCase {

    Optional<PixTransaction> findById(UUID transactionId);

    /** Lista do lojista (mais recentes primeiro), limitada a {@code limit}. */
    List<PixTransaction> findByMerchantId(UUID merchantId, int limit);
}
