package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuracao do saque automatico por limiar de saldo (Spec 70) —
 * {@code foxcash.auto-withdraw.*}.
 *
 * <p>Valores monetarios seguem o padrao do projeto ({@link PricingProperties}/
 * {@link PortalProperties}): {@link BigDecimal} no binding, convertido para
 * {@code Money} no servico ({@code Money.of}) — escala validada no dominio
 * (M9, Spec 26).
 */
@ConfigurationProperties("foxcash.auto-withdraw")
public class AutoWithdrawProperties {

    /** Kill-switch global: com {@code false} o job nao executa nenhum saque. */
    private boolean enabled = true;

    /** Cron do job (UTC). Default: a cada 5 minutos. */
    private String cron = "0 */5 * * * *";

    /** Limiar default devolvido no GET quando a config nao existe (R$ 1.000,00). */
    private BigDecimal defaultThreshold = new BigDecimal("1000.00");

    /** Piso de validacao do PUT (R$ 1,00). */
    private BigDecimal minThreshold = new BigDecimal("1.00");

    /** Teto de saques CRIADOS (Fase A OK) por execucao — skips nao contam. */
    private int maxWithdrawalsPerRun = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public BigDecimal getDefaultThreshold() {
        return defaultThreshold;
    }

    public void setDefaultThreshold(BigDecimal defaultThreshold) {
        this.defaultThreshold = defaultThreshold;
    }

    public BigDecimal getMinThreshold() {
        return minThreshold;
    }

    public void setMinThreshold(BigDecimal minThreshold) {
        this.minThreshold = minThreshold;
    }

    public int getMaxWithdrawalsPerRun() {
        return maxWithdrawalsPerRun;
    }

    public void setMaxWithdrawalsPerRun(int maxWithdrawalsPerRun) {
        this.maxWithdrawalsPerRun = maxWithdrawalsPerRun;
    }
}
