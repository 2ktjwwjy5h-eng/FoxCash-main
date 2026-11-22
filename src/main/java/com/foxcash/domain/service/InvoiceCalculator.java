package com.foxcash.domain.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.pricing.PricingPlan;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Constroi o rascunho (draft) de uma fatura mensal a partir do plano de precificacao
 * e da quantidade de transacoes aprovadas no periodo.
 *
 * <p>Spec 98 (D4.1): com override {@code FIXED} no lojista, cada transacao custa
 * {@code pix_fee_fixed} (mesma fonte do netAmount). O override {@code PERCENT}
 * depende do valor de cada venda e nao se aplica a agregacao por contagem —
 * a fatura usa a taxa do plano nesse caso (limitacao documentada).
 */
public class InvoiceCalculator {

    private final PricingCalculator pricingCalculator;

    public InvoiceCalculator(PricingCalculator pricingCalculator) {
        this.pricingCalculator = pricingCalculator;
    }

    public Invoice draft(UUID merchantId, BillingPeriod period, int transactionCount,
                         PricingPlan plan, MerchantConfig config, LocalDate dueDate) {
        Money fees = pricingCalculator.totalTransactionFees(plan, config, transactionCount);
        return Invoice.draft(merchantId, period, transactionCount, fees, plan.monthlyFee(), dueDate);
    }
}
