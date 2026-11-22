package com.foxcash.domain.port.in;

import com.foxcash.domain.model.billing.Invoice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de entrada: consultas de faturas.
 */
public interface QueryInvoiceUseCase {

    Optional<Invoice> findById(UUID invoiceId);

    /** Lista do lojista (mais recentes primeiro), limitada a {@code limit}. */
    List<Invoice> findByMerchantId(UUID merchantId, int limit);
}
