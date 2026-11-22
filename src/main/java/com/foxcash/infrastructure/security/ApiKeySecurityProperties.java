package com.foxcash.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuracao da autenticacao por API key gerenciavel (Spec 24) — Spec 65
 * (Fase 1): intervalo do throttle do {@code last_used_at}.
 *
 * <p>O UPDATE de uso ({@code merchant_api_keys.last_used_at}) era o hot spot de
 * escrita do caminho do charge (pg_stat_statements: 98.710 chamadas, media
 * 41ms). Com {@code record-use-interval}, o filtro grava o ultimo uso no maximo
 * 1x por intervalo por chave (por instancia — topologia multi-instancia
 * documentada na Spec 65/61) e o portal (Spec 24) mantem a granularidade de
 * minutos.
 */
@ConfigurationProperties("foxcash.security.api-key")
public class ApiKeySecurityProperties {

    /** Intervalo minimo entre UPDATEs de {@code last_used_at} de uma MESMA chave. */
    private Duration recordUseInterval = Duration.ofSeconds(60);

    public Duration getRecordUseInterval() {
        return recordUseInterval;
    }

    public void setRecordUseInterval(Duration recordUseInterval) {
        this.recordUseInterval = recordUseInterval;
    }
}
