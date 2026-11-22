package com.foxcash.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuracao do sweep zero-balance do saldo do PSP (Spec 92) —
 * {@code foxcash.psp.sweep.*}. Binding testado contra o yml real (padrao
 * Spec 68/L16). O sweep transfere o excedente (Disponivel − buffer) da conta
 * master do PSP para a conta blindada corporativa, protegendo o caixa contra
 * freeze.
 */
@ConfigurationProperties("foxcash.psp.sweep")
public class SweepProperties {

    /** Kill-switch global (default false — o sweep so roda quando ligado). */
    private boolean enabled = false;

    /** Cron do job (UTC, default 06:00 — fora da janela de pico). */
    private String cron = "0 0 6 * * *";

    /** Valor retido no PSP para os saques dos lojistas (R$). Default 0 = zero-balance estrito. */
    private BigDecimal bufferAmount = BigDecimal.ZERO;

    /** Chave Pix da conta blindada corporativa (EVP recomendada) — destino do excedente. */
    private String destinationKey;

    /** Limiar de exposicao (R$) que dispara o alerta {@code SWEEP_EXPOSURE_HIGH}. */
    private BigDecimal exposureAlert = new BigDecimal("10000");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public BigDecimal getBufferAmount() { return bufferAmount; }
    public void setBufferAmount(BigDecimal bufferAmount) { this.bufferAmount = bufferAmount; }

    public String getDestinationKey() { return destinationKey; }
    public void setDestinationKey(String destinationKey) { this.destinationKey = destinationKey; }

    public BigDecimal getExposureAlert() { return exposureAlert; }
    public void setExposureAlert(BigDecimal exposureAlert) { this.exposureAlert = exposureAlert; }

    /** Fail-fast no boot (Spec 92, criterio 7): enabled sem destino e buffer negativo
     * sao config quebrada — o job so pode existir com destino valido. Mesmo padrao
     * do {@code PspProperties.validate()}; em prod a falha impede o deploy. */
    @PostConstruct
    void validate() {
        if (enabled && (destinationKey == null || destinationKey.isBlank())) {
            throw new IllegalStateException(
                    "foxcash.psp.sweep.destination-key e obrigatorio quando o sweep esta habilitado (FOXCASH_PSP_SWEEP_DESTINATION_KEY)");
        }
        if (bufferAmount == null || bufferAmount.signum() < 0) {
            throw new IllegalStateException(
                    "foxcash.psp.sweep.buffer-amount nao pode ser negativo");
        }
    }
}
