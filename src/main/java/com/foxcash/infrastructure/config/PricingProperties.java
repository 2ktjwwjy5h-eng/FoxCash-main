package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuracao do modelo de precificacao fixa (R$ 0,50 por transacao + R$ 69,95 mensais).
 * Valores vem de application.yml (foxcash.pricing).
 */
@ConfigurationProperties(prefix = "foxcash.pricing")
public class PricingProperties {

    private BigDecimal transactionFee = new BigDecimal("0.50");
    private BigDecimal monthlyFee = new BigDecimal("69.95");

    /**
     * Taxa por saque do lojista (Spec 88 — default R$ 2,50). Incide em saques
     * manuais (Specs 14/32/36) e automáticos (Spec 70); o saque administrativo
     * do master é isento. Devolvida integralmente quando o saque falha no PSP.
     */
    private BigDecimal withdrawalFee = new BigDecimal("2.50");

    /**
     * Teto de cobranças PENDENTES por lojista (Spec 58 — baseline 100,
     * calibrar no benchmark). Acima disso, a criação de nova cobrança responde
     * 409 {@code too_many_pending_charges} (degradação controlada: o lojista
     * não acumula pendências ilimitadas; após o pagamento, volta a funcionar).
     */
    private int maxPendingCharges = 100;

    public BigDecimal getTransactionFee() {
        return transactionFee;
    }

    public void setTransactionFee(BigDecimal transactionFee) {
        this.transactionFee = transactionFee;
    }

    public BigDecimal getMonthlyFee() {
        return monthlyFee;
    }

    public void setMonthlyFee(BigDecimal monthlyFee) {
        this.monthlyFee = monthlyFee;
    }

    public BigDecimal getWithdrawalFee() {
        return withdrawalFee;
    }

    public void setWithdrawalFee(BigDecimal withdrawalFee) {
        this.withdrawalFee = withdrawalFee;
    }

    public int getMaxPendingCharges() {
        return maxPendingCharges;
    }

    public void setMaxPendingCharges(int maxPendingCharges) {
        this.maxPendingCharges = maxPendingCharges;
    }
}
