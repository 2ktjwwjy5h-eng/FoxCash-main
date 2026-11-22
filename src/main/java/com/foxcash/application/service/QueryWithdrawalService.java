package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalSearchQuery;
import com.foxcash.domain.port.in.QueryWithdrawalUseCase;
import com.foxcash.domain.port.out.WithdrawalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicação: consulta de saidas (saques) — somente-leitura.
 */
@Service
@Transactional(readOnly = true)
public class QueryWithdrawalService implements QueryWithdrawalUseCase {

    private final WithdrawalRepository withdrawalRepository;

    public QueryWithdrawalService(WithdrawalRepository withdrawalRepository) {
        this.withdrawalRepository = withdrawalRepository;
    }

    @Override
    public Optional<Withdrawal> findById(UUID withdrawalId) {
        return withdrawalRepository.findById(withdrawalId);
    }

    @Override
    public List<Withdrawal> findByMerchantId(UUID merchantId, int limit) {
        return withdrawalRepository.findByMerchantId(merchantId, limit);
    }

    @Override
    public PageResult<Withdrawal> search(WithdrawalSearchQuery query) {
        return withdrawalRepository.search(query);
    }
}
