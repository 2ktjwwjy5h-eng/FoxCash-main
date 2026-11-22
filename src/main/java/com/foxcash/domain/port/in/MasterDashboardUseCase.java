package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.master.MasterEarnings;
import com.foxcash.domain.model.master.MasterFees;
import com.foxcash.domain.model.master.MasterTransactionSearchQuery;
import com.foxcash.domain.model.transaction.PixTransaction;

import java.util.UUID;

/**
 * Caso de uso: dashboard do master (Spec 15) — leitura de transacoes, ganhos
 * e taxas. Somente-leitura (a execucao do master sao os controles de saque,
 * Spec 14).
 */
public interface MasterDashboardUseCase {

    PageResult<PixTransaction> transactions(MasterTransactionSearchQuery query);

    MasterEarnings earnings(UUID merchantId);

    MasterFees fees(UUID merchantId, BillingPeriod period);
}
