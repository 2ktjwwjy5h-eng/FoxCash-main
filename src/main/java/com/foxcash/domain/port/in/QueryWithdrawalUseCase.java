package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalSearchQuery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Caso de uso: consulta de saidas (saques) — do lojista (proprios) e do
 * master (todos, paginado com filtros). Somente-leitura.
 */
public interface QueryWithdrawalUseCase {

    Optional<Withdrawal> findById(UUID withdrawalId);

    /** Lista do lojista (mais recentes primeiro), limitada a {@code limit}. */
    List<Withdrawal> findByMerchantId(UUID merchantId, int limit);

    PageResult<Withdrawal> search(WithdrawalSearchQuery query);
}
