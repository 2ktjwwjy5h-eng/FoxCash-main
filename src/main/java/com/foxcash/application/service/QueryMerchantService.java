package com.foxcash.application.service;

import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.in.QueryMerchantUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Aplicacao: consultas de lojistas.
 */
@Service
@Transactional(readOnly = true)
public class QueryMerchantService implements QueryMerchantUseCase {

    private final MerchantRepository merchantRepository;

    public QueryMerchantService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Override
    public Optional<Merchant> findById(UUID merchantId) {
        return merchantRepository.findById(merchantId);
    }
}
