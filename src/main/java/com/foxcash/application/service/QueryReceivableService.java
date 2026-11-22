package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableSearchQuery;
import com.foxcash.domain.port.in.QueryReceivableUseCase;
import com.foxcash.domain.port.out.ReceivableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplicacao: consulta de receivables (Spec 33) — somente-leitura, ROLE_MASTER.
 */
@Service
@Transactional(readOnly = true)
public class QueryReceivableService implements QueryReceivableUseCase {

    private final ReceivableRepository receivableRepository;

    public QueryReceivableService(ReceivableRepository receivableRepository) {
        this.receivableRepository = receivableRepository;
    }

    @Override
    public PageResult<Receivable> search(ReceivableSearchQuery query) {
        return receivableRepository.search(query);
    }
}
