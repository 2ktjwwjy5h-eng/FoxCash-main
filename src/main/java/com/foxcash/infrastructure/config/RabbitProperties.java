package com.foxcash.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config da fila dedicada RabbitMQ do outbox (Spec 134) — {@code foxcash.rabbit.*}.
 *
 * <p>A feature é <b>opt-in</b> ({@code foxcash.rabbit.enabled=false} default):
 * sem broker configurado, o outbox segue o caminho atual (tabela + worker
 * {@code NotificationDispatcherService} em modo dreno). Com {@code enabled=true},
 * o {@code OutboxRelay} publica os eventos na fila após o commit e o
 * {@code OutboxConsumer} entrega (webhook/notificação) com latência de segundos.
 *
 * <p>Fail-safe ADR-010: se o publish falhar (broker down), a mensagem permanece
 * no outbox DB e o worker atual entrega — zero perda.
 */
@ConfigurationProperties("foxcash.rabbit")
public class RabbitProperties {

    /** Liga/desliga a fila dedicada (opt-in — default desligada). */
    private boolean enabled = false;

    /** Nome do exchange do outbox (direct). */
    private String exchange = "foxcash.outbox";

    /** Fila principal do outbox (bind no exchange). */
    private String queue = "foxcash.outbox.queue";

    /** Fila morta (DLQ) — mensagens que falharam N vezes. */
    private String deadLetterQueue = "foxcash.outbox.dlq";

    /** Routing key do bind fila ↔ exchange. */
    private String routingKey = "outbox";

    /** Máximo de tentativas do consumidor antes da DLQ (default 3). */
    private int maxAttempts = 3;

    /** Intervalo inicial do backoff do retry do consumidor (ms). */
    private long retryInitialIntervalMs = 1_000;

    /** Multiplicador do backoff exponencial do retry. */
    private double retryMultiplier = 2.0;

    /** Intervalo máximo do backoff do retry (ms). */
    private long retryMaxIntervalMs = 30_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getDeadLetterQueue() {
        return deadLetterQueue;
    }

    public void setDeadLetterQueue(String deadLetterQueue) {
        this.deadLetterQueue = deadLetterQueue;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryInitialIntervalMs() {
        return retryInitialIntervalMs;
    }

    public void setRetryInitialIntervalMs(long retryInitialIntervalMs) {
        this.retryInitialIntervalMs = retryInitialIntervalMs;
    }

    public double getRetryMultiplier() {
        return retryMultiplier;
    }

    public void setRetryMultiplier(double retryMultiplier) {
        this.retryMultiplier = retryMultiplier;
    }

    public long getRetryMaxIntervalMs() {
        return retryMaxIntervalMs;
    }

    public void setRetryMaxIntervalMs(long retryMaxIntervalMs) {
        this.retryMaxIntervalMs = retryMaxIntervalMs;
    }
}
