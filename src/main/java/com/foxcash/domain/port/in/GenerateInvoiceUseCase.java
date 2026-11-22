package com.foxcash.domain.port.in;

import com.foxcash.domain.model.billing.Invoice;

import java.time.YearMonth;
import java.util.UUID;

/**
 * Porta de entrada: gera a fatura mensal de um lojista para um periodo.
 */
public interface GenerateInvoiceUseCase {

    Invoice generateForPeriod(UUID merchantId, YearMonth period);
}
