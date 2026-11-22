package com.foxcash.domain.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.pricing.PricingPlan;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculos do modelo de precificacao fixa FoxCash:
 * R$ 0,50 por transacao + R$ 69,95 mensais.
 *
 * <p>Spec 98 (D4.1): a config fina do lojista pode SOBRESCREVER a taxa por
 * transacao do plano — {@code FIXED} cobra {@code pix_fee_fixed} por
 * transacao; {@code PERCENT} cobra {@code pix_fee_percent}% quando
 * {@code amount > pix_fee_threshold}, senão {@code pix_fee_fixed} (regra da
 * descrição). Sem override (config {@code null} ou {@code pix_fee_type}
 * {@code null}) → taxa do plano (fallback global — nunca menos restritivo por
 * acidente). Método puro (sem porta): o chamador resolve a config do lojista e
 * passa o valor da transação.
 */
public class PricingCalculator {

    public Money transactionFee(PricingPlan plan) {
        return plan.transactionFee();
    }

    /**
     * Taxa por transação com override da config fina (Spec 98 — D4.1).
     * {@code amount} é o valor da transação (necessário para a regra PERCENT);
     * {@code null} quando indisponível → usa a taxa do plano (fallback global).
     */
    public Money transactionFee(PricingPlan plan, MerchantConfig config, Money amount) {
        if (config == null || config.pixFeeType() == null) {
            return plan.transactionFee();
        }
        if (config.pixFeeType() == MerchantConfig.PixFeeType.FIXED) {
            return config.pixFeeFixed();
        }
        // PERCENT: acima do threshold cobra %; ate o threshold, fixa (regra da descricao).
        if (amount != null && config.pixFeeThreshold() != null
                && amount.amount().compareTo(config.pixFeeThreshold().amount()) > 0) {
            BigDecimal percentFee = amount.amount()
                    .multiply(config.pixFeePercent())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
            return Money.of(percentFee);
        }
        return config.pixFeeFixed();
    }

    /**
     * Agrega as taxas de N transacoes considerando o override FIXED (Spec 98):
     * com {@code FIXED}, cada transacao custa {@code pix_fee_fixed}; com
     * {@code PERCENT} ou sem override, a fatura mensal (agregada por CONTAGEM —
     * sem valores por transacao) usa a taxa do plano (limitação documentada:
     * o PERCENT depende do valor de cada venda e só é aplicado no netAmount
     * por transação, Spec 25/97).
     */
    public Money totalTransactionFees(PricingPlan plan, MerchantConfig config, int transactionCount) {
        if (config != null && config.pixFeeType() == MerchantConfig.PixFeeType.FIXED) {
            return config.pixFeeFixed().multiply(transactionCount);
        }
        return totalTransactionFees(plan, transactionCount);
    }

    public Money totalTransactionFees(PricingPlan plan, int transactionCount) {
        return plan.transactionFee().multiply(transactionCount);
    }

    public Money monthlyTotal(PricingPlan plan, int transactionCount) {
        return totalTransactionFees(plan, transactionCount).add(plan.monthlyFee());
    }
}
