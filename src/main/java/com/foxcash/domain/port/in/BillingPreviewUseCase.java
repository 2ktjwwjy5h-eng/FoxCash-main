package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;

import java.time.YearMonth;
import java.util.UUID;

/**
 * Porta de entrada: previsao (preview) da fatura mensal de um lojista,
 * sem persistir nada.
 */
public interface BillingPreviewUseCase {

    Preview preview(UUID merchantId, YearMonth month);

    record Preview(int totalTransactions, Money variableFee, Money fixedFee, Money totalDue,
                   String calculation) {}
}
