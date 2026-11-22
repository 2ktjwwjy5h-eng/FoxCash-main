package com.foxcash.application.service;

import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.in.QueryTransactionUseCase;
import com.foxcash.domain.port.out.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicacao: consultas de transacoes Pix.
 */
@Service
@Transactional(readOnly = true)
public class QueryTransactionService implements QueryTransactionUseCase {

    private final TransactionRepository transactionRepository;

    public QueryTransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Optional<PixTransaction> findById(UUID transactionId) {
        return transactionRepository.findById(transactionId);
    }

    @Override
    public List<PixTransaction> findByMerchantId(UUID merchantId, int limit) {
        return transactionRepository.findByMerchantId(merchantId, limit);
    }
}
