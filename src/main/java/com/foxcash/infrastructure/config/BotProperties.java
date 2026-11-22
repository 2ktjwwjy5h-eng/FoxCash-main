package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do registro central de bots de venda (Spec 74) —
 * {@code foxcash.bots.*}. Binding testado contra o {@code application.yml} real
 * (padrao Spec 68/L16).
 */
@ConfigurationProperties("foxcash.bots")
public class BotProperties {

    /**
     * URL publica base usada no registro do webhook do Telegram
     * ({@code https://<host>/webhooks/telegram/{id}/{secret}}). Vazio (dev) =
     * registra com URL de dev ({@code http://localhost:8080}) — o E2E aponta
     * para o mock do Telegram e o registro nao depende de URL publica real.
     */
    private String webhookPublicBaseUrl = "";

    /** Kill-switch do job de reconciliacao ({@code foxcash-bot-reconcile}). */
    private boolean reconcileEnabled = true;

    /** Cron do job de reconciliacao (UTC). Default: a cada 5 minutos. */
    private String reconcileCron = "0 */5 * * * *";

    /**
     * Kill-switch da conexao de gateway do Discord. Default {@code false} (off
     * em dev/test — o registro nao depende de gateway real); em producao o
     * gerenciador conecta/valida via API.
     */
    private boolean discordGatewayEnabled = false;

    /** Base URL da Bot API do Telegram (configuravel p/ o mock de contrato no E2E). */
    private String telegramApiBaseUrl = "https://api.telegram.org";

    /** Base URL da API do Discord v10 (configuravel p/ o mock de contrato no E2E). */
    private String discordApiBaseUrl = "https://discord.com/api/v10";

    /**
     * Canal CDN do Telegram (Spec 79/D7): o upload de mídia do broadcast envia
     * o binário para este canal uma vez e reusa o {@code file_id} nos disparos —
     * o bot NUNCA re-uploada por destinatário. Vazio (dev/test) = upload sem
     * canal CDN configurado (falha com mensagem clara).
     */
    private String telegramCdnChannelId = "";

    /** Tempo (min) para um registro REGISTERING preso virar ERROR (job reconcile). */
    private int registeringTimeoutMinutes = 30;

    /** Tempo (min) sem health check para um ACTIVE ser re-verificado (job reconcile). */
    private int healthTimeoutMinutes = 60;

    public String getWebhookPublicBaseUrl() {
        return webhookPublicBaseUrl;
    }

    public void setWebhookPublicBaseUrl(String webhookPublicBaseUrl) {
        this.webhookPublicBaseUrl = webhookPublicBaseUrl;
    }

    public boolean isReconcileEnabled() {
        return reconcileEnabled;
    }

    public void setReconcileEnabled(boolean reconcileEnabled) {
        this.reconcileEnabled = reconcileEnabled;
    }

    public String getReconcileCron() {
        return reconcileCron;
    }

    public void setReconcileCron(String reconcileCron) {
        this.reconcileCron = reconcileCron;
    }

    public boolean isDiscordGatewayEnabled() {
        return discordGatewayEnabled;
    }

    public void setDiscordGatewayEnabled(boolean discordGatewayEnabled) {
        this.discordGatewayEnabled = discordGatewayEnabled;
    }

    public String getTelegramApiBaseUrl() {
        return telegramApiBaseUrl;
    }

    public void setTelegramApiBaseUrl(String telegramApiBaseUrl) {
        this.telegramApiBaseUrl = telegramApiBaseUrl;
    }

    public String getDiscordApiBaseUrl() {
        return discordApiBaseUrl;
    }

    public void setDiscordApiBaseUrl(String discordApiBaseUrl) {
        this.discordApiBaseUrl = discordApiBaseUrl;
    }

    public String getTelegramCdnChannelId() {
        return telegramCdnChannelId;
    }

    public void setTelegramCdnChannelId(String telegramCdnChannelId) {
        this.telegramCdnChannelId = telegramCdnChannelId;
    }

    public int getRegisteringTimeoutMinutes() {
        return registeringTimeoutMinutes;
    }

    public void setRegisteringTimeoutMinutes(int registeringTimeoutMinutes) {
        this.registeringTimeoutMinutes = registeringTimeoutMinutes;
    }

    public int getHealthTimeoutMinutes() {
        return healthTimeoutMinutes;
    }

    public void setHealthTimeoutMinutes(int healthTimeoutMinutes) {
        this.healthTimeoutMinutes = healthTimeoutMinutes;
    }
}
