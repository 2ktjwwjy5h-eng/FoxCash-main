package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuracao do roteamento de cobranca Pix por adquirente (Spec 91):
 * bloco {@code foxcash.psp.routing.*}.
 *
 * <ul>
 *   <li>{@code enabled} — kill-switch do router (false = adapter unico atual);</li>
 *   <li>{@code providers} — lista global de prioridade (ex.: "pagarme,asaas")
 *       usada quando nao ha policies configuradas (ou como fallback);</li>
 *   <li>{@code policies} — rotas por risco do lojista (D4.1): {@code default} +
 *       faixas com {@code score-min} (ex.: {@code high-risk: {providers: "suitpay",
 *       score-min: 60}}); listas DISJUNTAS por design (isolamento por nicho);</li>
 *   <li>{@code circuit-breaker} — janela/limiar/cooldown do {@code PixProviderHealthRegistry}
 *       + kill-switch por provedor ({@code providers.<p>.enabled=false}).</li>
 * </ul>
 */
@ConfigurationProperties("foxcash.psp.routing")
public class RoutingProperties {

    /** false (default) = adapter unico atual ({@code foxcash.psp.provider}). */
    private boolean enabled = false;

    /** Lista global de provedores em ordem de prioridade (ex.: "pagarme,asaas"). */
    private String providers = "";

    /** Políticas por risco do lojista (D4.1) — {@code default} + faixas. */
    private Map<String, Policy> policies = new HashMap<>();

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProviders() {
        return providers;
    }

    public void setProviders(String providers) {
        this.providers = providers;
    }

    public Map<String, Policy> getPolicies() {
        return policies;
    }

    public void setPolicies(Map<String, Policy> policies) {
        this.policies = policies;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    /** Lista de prioridade de uma faixa de risco (D4.1). */
    public static class Policy {

        /** Provedores em ordem de prioridade (ex.: "suitpay,asaas"). */
        private String providers = "";

        /** Limiar da faixa: aplica quando {@code risk_score >= scoreMin}. */
        private int scoreMin = 0;

        public String getProviders() {
            return providers;
        }

        public void setProviders(String providers) {
            this.providers = providers;
        }

        public int getScoreMin() {
            return scoreMin;
        }

        public void setScoreMin(int scoreMin) {
            this.scoreMin = scoreMin;
        }
    }

    /** Circuit breaker preventivo (D5). */
    public static class CircuitBreaker {

        /** Janela de falhas (segundos). */
        private long windowSeconds = 60;

        /** Numero de falhas na janela para abrir o circuito. */
        private int failureThreshold = 5;

        /** Tempo de cooldown do circuito OPEN (segundos) antes de HALF-OPEN. */
        private long cooldownSeconds = 300;

        /** Kill-switch por provedor (chave = nome do provedor). */
        private Map<String, ProviderCircuit> providers = new HashMap<>();

        public long getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(long windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getCooldownSeconds() {
            return cooldownSeconds;
        }

        public void setCooldownSeconds(long cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
        }

        public Map<String, ProviderCircuit> getProviders() {
            return providers;
        }

        public void setProviders(Map<String, ProviderCircuit> providers) {
            this.providers = providers;
        }
    }

    /** Kill-switch manual por provedor. */
    public static class ProviderCircuit {

        /** true (default) = provedor participa do roteamento. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
