package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Configuracao do portal do lojista (Spec 20/46) — {@code foxcash.portal.*}.
 */
@ConfigurationProperties("foxcash.portal")
public class PortalProperties {

    /** Validade da sessao do portal em segundos (default 8h — mesmo padrao do master). */
    private long sessionTtlSeconds = 8 * 60 * 60;

    /**
     * Meta da "jornada do player" (Spec 21, gamificacao do lojista) — volume
     * aprovado em reais para atingir 100% de progresso. 0/ausente = progresso 0.
     */
    private BigDecimal journeyGoalAmount = BigDecimal.valueOf(1000.00);

    /**
     * Limite maximo de uma cobranca criada pelo portal (Spec 23) —
     * {@code foxcash.portal.charge-max-amount}. Valores acima → 400 invalid_argument.
     */
    private BigDecimal chargeMaxAmount = BigDecimal.valueOf(10000.00);

    /**
     * Limite de destinos de saque ATIVOS por lojista (Spec 31) —
     * {@code foxcash.portal.max-withdrawal-destinations}. Excedido → 409
     * {@code too_many_destinations}.
     */
    private int maxWithdrawalDestinations = 5;

    /**
     * Spec 46: serva a SPA do portal (build estatico em
     * {@code src/main/resources/static}) — {@code foxcash.portal.serve-frontend}.
     * Default {@code false}: mantem a CSP restrita da API pura e nenhuma rota
     * extra exposta. Com {@code true}, ativa a CSP da SPA, as rotas publicas
     * (/login, /dashboard, ...) e o fallback para o {@code index.html}.
     */
    private boolean serveFrontend = false;

    /**
     * Spec 46: origins permitidos em CORS para consumo externo da API do portal
     * (ex.: a SPA hospedada em outro dominio) — {@code foxcash.portal.cors-allowed-origins}.
     * Vazio (default) = CORS desabilitado (mesmo origin, sem header).
     */
    private List<String> corsAllowedOrigins = List.of();

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public BigDecimal getJourneyGoalAmount() {
        return journeyGoalAmount;
    }

    public void setJourneyGoalAmount(BigDecimal journeyGoalAmount) {
        this.journeyGoalAmount = journeyGoalAmount;
    }

    public BigDecimal getChargeMaxAmount() {
        return chargeMaxAmount;
    }

    public void setChargeMaxAmount(BigDecimal chargeMaxAmount) {
        this.chargeMaxAmount = chargeMaxAmount;
    }

    public int getMaxWithdrawalDestinations() {
        return maxWithdrawalDestinations;
    }

    public void setMaxWithdrawalDestinations(int maxWithdrawalDestinations) {
        this.maxWithdrawalDestinations = maxWithdrawalDestinations;
    }

    public boolean isServeFrontend() {
        return serveFrontend;
    }

    public void setServeFrontend(boolean serveFrontend) {
        this.serveFrontend = serveFrontend;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }
}
