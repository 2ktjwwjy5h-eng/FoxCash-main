package com.foxcash.application.service;

import com.foxcash.domain.model.fraud.Chargeback;
import com.foxcash.domain.port.in.QueryChargebackUseCase;
import com.foxcash.domain.port.out.ChargebackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Aplicacao: consulta de chargebacks (Spec 19) — revisao do master.
 */
@Service
public class QueryChargebackService implements QueryChargebackUseCase {

    private final ChargebackRepository chargebackRepository;

    public QueryChargebackService(ChargebackRepository chargebackRepository) {
        this.chargebackRepository = chargebackRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Chargeback findById(UUID chargebackId) {
        return chargebackRepository.findById(chargebackId)
                .orElseThrow(() -> new IllegalStateException("Chargeback inexistente"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chargeback> findRecent(int limit) {
        return chargebackRepository.findRecent(limit);
    }
}
