package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.risk.MerchantRiskSnapshot;
import com.foxcash.domain.model.risk.RiskSearchQuery;
import com.foxcash.domain.port.in.QueryRiskUseCase;
import com.foxcash.domain.port.out.RiskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Aplicação: consulta de snapshots de risco (Spec 16) — somente-leitura.
 */
@Service
@Transactional(readOnly = true)
public class QueryRiskService implements QueryRiskUseCase {

    private final RiskRepository riskRepository;

    public QueryRiskService(RiskRepository riskRepository) {
        this.riskRepository = riskRepository;
    }

    @Override
    public PageResult<MerchantRiskSnapshot> search(RiskSearchQuery query) {
        return riskRepository.search(query);
    }

    @Override
    public Optional<MerchantRiskSnapshot> latestByMerchant(UUID merchantId) {
        return riskRepository.latestByMerchant(merchantId);
    }
}
