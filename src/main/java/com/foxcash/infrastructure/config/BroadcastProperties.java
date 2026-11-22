package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do broadcast (Spec 79/D11) — {@code foxcash.broadcast.*}.
 * Binding testado contra o {@code application.yml} real (padrao Spec 68/L16).
 */
@ConfigurationProperties("foxcash.broadcast")
public class BroadcastProperties {

    /** Kill-switch do job de re-agendamento do REPOST. */
    private boolean repostEnabled = true;

    /** Cron do job de re-agendamento (UTC). Default: a cada 5 minutos. */
    private String repostCron = "0 */5 * * * *";

    public boolean isRepostEnabled() {
        return repostEnabled;
    }

    public void setRepostEnabled(boolean repostEnabled) {
        this.repostEnabled = repostEnabled;
    }

    public String getRepostCron() {
        return repostCron;
    }

    public void setRepostCron(String repostCron) {
        this.repostCron = repostCron;
    }
}
