package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.master.MasterEarnings;
import com.foxcash.domain.model.master.MasterFees;
import com.foxcash.domain.model.master.MasterTransactionSearchQuery;
import com.foxcash.domain.model.transaction.PixTransaction;

import java.util.UUID;

/**
 * Porta de saida: leitura agregada do dashboard master (Spec 15).
 */
public interface MasterDashboardPort {

    PageResult<PixTransaction> findTransactions(MasterTransactionSearchQuery query);

    MasterEarnings earnings(UUID merchantId);

    MasterFees fees(UUID merchantId, BillingPeriod period);
}
