package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.master.MasterEarnings;
import com.foxcash.domain.model.master.MasterFees;
import com.foxcash.domain.model.master.MasterTransactionSearchQuery;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.in.MasterDashboardUseCase;
import com.foxcash.domain.port.out.MasterDashboardPort;
import com.foxcash.domain.port.out.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Aplicação: dashboard do master (Spec 15) — somente-leitura.
 *
 * <p>Earnings/fees validam a existencia do lojista (404) e delegam as
 * agregacoes ao port (mesmo padrao do AdminDashboardService, Spec 8).
 */
@Service
@Transactional(readOnly = true)
public class MasterDashboardService implements MasterDashboardUseCase {

    private final MasterDashboardPort masterDashboardPort;
    private final MerchantRepository merchantRepository;

    public MasterDashboardService(MasterDashboardPort masterDashboardPort,
                                  MerchantRepository merchantRepository) {
        this.masterDashboardPort = masterDashboardPort;
        this.merchantRepository = merchantRepository;
    }

    @Override
    public PageResult<PixTransaction> transactions(MasterTransactionSearchQuery query) {
        return masterDashboardPort.findTransactions(query);
    }

    @Override
    public MasterEarnings earnings(UUID merchantId) {
        requireMerchant(merchantId);
        return masterDashboardPort.earnings(merchantId);
    }

    @Override
    public MasterFees fees(UUID merchantId, BillingPeriod period) {
        requireMerchant(merchantId);
        return masterDashboardPort.fees(merchantId, period);
    }

    private void requireMerchant(UUID merchantId) {
        if (merchantRepository.findById(merchantId).isEmpty()) {
            throw new MerchantNotFoundException(merchantId);
        }
    }
}
