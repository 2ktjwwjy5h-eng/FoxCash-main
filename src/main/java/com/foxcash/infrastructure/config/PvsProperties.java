package com.foxcash.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuracao do Pagamento via Saldo (Spec 120) — {@code foxcash.pvs.*}.
 *
 * <p>Tetos anti-abuso (destino arbitrário = risco maior): teto diário por
 * lojista ({@code daily-cap}, default R$ 5.000,00 — soma {@code amount+fee} de
 * TODOS os PVS criados no dia, qualquer status) e teto por operação
 * ({@code max-per-payment}, default R$ 1.000,00 — valor + taxa). Avaliados
 * dentro da Fase A sob o lock do lojista (SEC-19). Valores operador-ajustáveis
 * por env; não são limites fixos de produto.
 */
@ConfigurationProperties("foxcash.pvs")
public class PvsProperties {

    private BigDecimal dailyCap = new BigDecimal("5000.00");
    private BigDecimal maxPerPayment = new BigDecimal("1000.00");

    public BigDecimal getDailyCap() {
        return dailyCap;
    }

    public void setDailyCap(BigDecimal dailyCap) {
        this.dailyCap = dailyCap;
    }

    public BigDecimal getMaxPerPayment() {
        return maxPerPayment;
    }

    public void setMaxPerPayment(BigDecimal maxPerPayment) {
        this.maxPerPayment = maxPerPayment;
    }

    @PostConstruct
    void validate() {
        if (dailyCap == null || dailyCap.signum() <= 0) {
            throw new IllegalStateException(
                    "foxcash.pvs.daily-cap deve ser > 0 (FOXCASH_PVS_DAILY_CAP)");
        }
        if (maxPerPayment == null || maxPerPayment.signum() <= 0) {
            throw new IllegalStateException(
                    "foxcash.pvs.max-per-payment deve ser > 0 (FOXCASH_PVS_MAX_PER_PAYMENT)");
        }
    }
}
