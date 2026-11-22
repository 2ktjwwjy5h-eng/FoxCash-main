package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Limiares de risco (Spec 16, ADR-008) — {@code foxcash.risk.*}.
 *
 * <p>Defaults alinhados às Políticas de Uso §4: chargeback > 1% (alerta) /
 * > 3% (suspensão), reembolso > 5% (alerta), denúncias 3+ em 30 dias (alerta).
 *
 * <p><b>Spec 128:</b> o bloco {@code auto} configura o Risk Scoring Engine
 * (única ação automática aprovada — reduzir rate limit por MED 30d); o
 * {@code auto-suspend-enabled} da Spec 19 passa a <b>default {@code false}</b>
 * (decisão do usuário 2026-08-16: sem bloqueio de conta automático —
 * verticais de produtos digitais têm chargeback recorrente).
 */
@ConfigurationProperties("foxcash.risk")
public class RiskProperties {

    /** Chargeback acima deste % liga o alerta. */
    private BigDecimal chargebackAlertPercent = BigDecimal.valueOf(1.0);

    /** Chargeback acima deste % sugere suspensao (decisao do master). */
    private BigDecimal chargebackSuspendPercent = BigDecimal.valueOf(3.0);

    /** Reembolsos acima deste % ligam o alerta. */
    private BigDecimal refundAlertPercent = BigDecimal.valueOf(5.0);

    /** Denuncias neste ou maior numero (30 dias) ligam o alerta. */
    private int complaintsAlertCount = 3;

    /**
     * Suspende o lojista automaticamente quando o chargeback >= limiar duro
     * (Spec 19) — pode ser desligado para operacao apenas-consulta. <b>Spec
     * 128: default {@code false}</b> (decisão do usuário 2026-08-16 — sem
     * bloqueio de conta automático; reativável por config se a operação pedir).
     */
    private boolean autoSuspendEnabled = false;

    /** Spec 128: regras do Risk Scoring Engine ({@code foxcash.risk.auto.*}). */
    private RiskAutoProperties auto = new RiskAutoProperties();

    public RiskAutoProperties getAuto() {
        return auto;
    }

    public void setAuto(RiskAutoProperties auto) {
        this.auto = auto;
    }

    public boolean isAutoSuspendEnabled() {
        return autoSuspendEnabled;
    }

    public void setAutoSuspendEnabled(boolean autoSuspendEnabled) {
        this.autoSuspendEnabled = autoSuspendEnabled;
    }

    public BigDecimal getChargebackAlertPercent() {
        return chargebackAlertPercent;
    }

    public void setChargebackAlertPercent(BigDecimal chargebackAlertPercent) {
        this.chargebackAlertPercent = chargebackAlertPercent;
    }

    public BigDecimal getChargebackSuspendPercent() {
        return chargebackSuspendPercent;
    }

    public void setChargebackSuspendPercent(BigDecimal chargebackSuspendPercent) {
        this.chargebackSuspendPercent = chargebackSuspendPercent;
    }

    public BigDecimal getRefundAlertPercent() {
        return refundAlertPercent;
    }

    public void setRefundAlertPercent(BigDecimal refundAlertPercent) {
        this.refundAlertPercent = refundAlertPercent;
    }

    public int getComplaintsAlertCount() {
        return complaintsAlertCount;
    }

    public void setComplaintsAlertCount(int complaintsAlertCount) {
        this.complaintsAlertCount = complaintsAlertCount;
    }

    /**
     * Risk Scoring Engine (Spec 128) — {@code foxcash.risk.auto.*}. Regra
     * declarativa, defaults conservadores (não age em variação normal).
     */
    public static class RiskAutoProperties {

        /** Kill-switch global — {@code false} → o engine não age. */
        private boolean enabled = true;

        /** MED 30d acima deste % → reduzir o rate limit (default 10%). */
        private BigDecimal medThresholdPercent = BigDecimal.valueOf(10.0);

        /** MED 30d ≤ este % → restaurar o rate limit (histerese; default 5%). */
        private BigDecimal medRecoverPercent = BigDecimal.valueOf(5.0);

        /** % de redução do rate limit sobre a referência (default 50%). */
        private BigDecimal rateLimitReductionPercent = BigDecimal.valueOf(50.0);

        /** Referência rps para a redução quando o lojista não tem override. */
        private int defaultRps = 5;

        /** Referência rpm para a redução quando o lojista não tem override. */
        private int defaultRpm = 300;

        /** Piso rps — a redução nunca fica abaixo disto (default 1). */
        private int floorRps = 1;

        /** Piso rpm — a redução nunca fica abaixo disto (default 60). */
        private int floorRpm = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public BigDecimal getMedThresholdPercent() {
            return medThresholdPercent;
        }

        public void setMedThresholdPercent(BigDecimal medThresholdPercent) {
            this.medThresholdPercent = medThresholdPercent;
        }

        public BigDecimal getMedRecoverPercent() {
            return medRecoverPercent;
        }

        public void setMedRecoverPercent(BigDecimal medRecoverPercent) {
            this.medRecoverPercent = medRecoverPercent;
        }

        public BigDecimal getRateLimitReductionPercent() {
            return rateLimitReductionPercent;
        }

        public void setRateLimitReductionPercent(BigDecimal rateLimitReductionPercent) {
            this.rateLimitReductionPercent = rateLimitReductionPercent;
        }

        public int getDefaultRps() {
            return defaultRps;
        }

        public void setDefaultRps(int defaultRps) {
            this.defaultRps = defaultRps;
        }

        public int getDefaultRpm() {
            return defaultRpm;
        }

        public void setDefaultRpm(int defaultRpm) {
            this.defaultRpm = defaultRpm;
        }

        public int getFloorRps() {
            return floorRps;
        }

        public void setFloorRps(int floorRps) {
            this.floorRps = floorRps;
        }

        public int getFloorRpm() {
            return floorRpm;
        }

        public void setFloorRpm(int floorRpm) {
            this.floorRpm = floorRpm;
        }
    }
}
