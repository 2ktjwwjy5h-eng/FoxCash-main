package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableSearchQuery;

/**
 * Caso de uso: consulta de receivables (Spec 33) — somente-leitura, ROLE_MASTER.
 */
public interface QueryReceivableUseCase {

    PageResult<Receivable> search(ReceivableSearchQuery query);
}
