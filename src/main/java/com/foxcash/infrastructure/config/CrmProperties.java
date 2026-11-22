package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do CRM (Specs 83/84) — {@code foxcash.crm.*}. Binding testado
 * contra o {@code application.yml} real (padrao Spec 68/L16).
 */
@ConfigurationProperties("foxcash.crm")
public class CrmProperties {

    /** Heartbeat do SSE (mantem a conexao viva apos proxies). */
    private long sseHeartbeatMs = 15000;

    /** Eventos em memoria por lojista para o {@code Last-Event-ID} (buffer curto). */
    private int sseBufferSize = 50;

    /** Prefixo do canal Redis pub/sub do fan-out multi-instancia. */
    private String chatEventChannelPrefix = "foxcash:crm:events:";

    /**
     * Fan-out via Redis pub/sub (false = so entrega local in-memory). Fail-open
     * (ADR-010): com Redis indisponivel a entrega local continua funcionando.
     */
    private boolean redisEnabled = true;

    public long getSseHeartbeatMs() { return sseHeartbeatMs; }
    public void setSseHeartbeatMs(long sseHeartbeatMs) { this.sseHeartbeatMs = sseHeartbeatMs; }

    public int getSseBufferSize() { return sseBufferSize; }
    public void setSseBufferSize(int sseBufferSize) { this.sseBufferSize = sseBufferSize; }

    public String getChatEventChannelPrefix() { return chatEventChannelPrefix; }
    public void setChatEventChannelPrefix(String chatEventChannelPrefix) {
        this.chatEventChannelPrefix = chatEventChannelPrefix;
    }

    public boolean isRedisEnabled() { return redisEnabled; }
    public void setRedisEnabled(boolean redisEnabled) { this.redisEnabled = redisEnabled; }
}
