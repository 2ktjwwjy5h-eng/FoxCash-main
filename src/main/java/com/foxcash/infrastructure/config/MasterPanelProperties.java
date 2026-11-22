package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Configuracao do painel de usuarios do Master Console (Spec 97) —
 * {@code foxcash.master-panel.*}.
 *
 * <p>{@code risk-thresholds} define as faixas do filtro {@code risk}
 * (baixo/medio/alto) sobre o {@code medPercent} derivado ao vivo: MED &lt;=
 * {@code low-percent} → baixo; MED &lt;= {@code mid-percent} → medio; acima →
 * alto. Defaults alinhados as politicas de uso da Spec 16 (2% / 5%). Valores
 * de exibicao (nao fail-fast): sem config, os defaults sao usados.
 */
@ConfigurationProperties("foxcash.master-panel")
public class MasterPanelProperties {

    private RiskThresholds riskThresholds = new RiskThresholds();

    public RiskThresholds getRiskThresholds() {
        return riskThresholds;
    }

    public void setRiskThresholds(RiskThresholds riskThresholds) {
        this.riskThresholds = riskThresholds;
    }

    /** Faixas do filtro {@code risk} sobre o {@code medPercent} (Spec 97 D6). */
    public static class RiskThresholds {

        /** MED &lt;= este percentual → risco baixo (default 2.0). */
        private BigDecimal lowPercent = BigDecimal.valueOf(2.0);

        /** MED &lt;= este percentual → risco medio (default 5.0); acima → alto. */
        private BigDecimal midPercent = BigDecimal.valueOf(5.0);

        public BigDecimal getLowPercent() {
            return lowPercent;
        }

        public void setLowPercent(BigDecimal lowPercent) {
            this.lowPercent = lowPercent;
        }

        public BigDecimal getMidPercent() {
            return midPercent;
        }

        public void setMidPercent(BigDecimal midPercent) {
            this.midPercent = midPercent;
        }
    }
}
