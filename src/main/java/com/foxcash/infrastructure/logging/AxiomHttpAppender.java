package com.foxcash.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appender HTTP para o Axiom (Spec 95 — log drain direto, sem depender do drain
 * da plataforma Railway). Serializa cada evento com um {@link Encoder} JSON
 * (LogstashEncoder do logstash-logback-encoder) e POSTa lotes no endpoint de
 * ingest do Axiom.
 *
 * <p>Design (fail-open — logs NUNCA derrubam ou bloqueiam a aplicação):
 * <ul>
 *   <li><b>Assíncrono</b>: fila limitada {@link ArrayBlockingQueue} + thread
 *       daemon. A chamada de log apenas enfileira (offer) — não faz I/O.</li>
 *   <li><b>Fila cheia</b>: descarta o evento e conta (aviso via StatusManager
 *       no 1º e a cada 1000 descartes) — o request da API não espera.</li>
 *   <li><b>Axiom fora / erro HTTP</b>: loga aviso e segue — o lote é perdido,
 *       a aplicação não.</li>
 *   <li><b>Config ausente</b> (ex.: sem AXIOM_API_TOKEN no ambiente):
 *       {@code start()} falha aberto — appender fica desligado, app sobe.</li>
 * </ul>
 *
 * <p>Warnings são emitidos via StatusManager ({@code addWarn}) — NUNCA via
 * SLF4J/logger, para não realimentar o próprio appender (loop infinito).
 *
 * <p>Configuração (logback-spring.xml, profile prod): url do ingest do Axiom,
 * token (env {@code AXIOM_API_TOKEN} ou fallback do atributo), maxQueueSize,
 * batchSize e o encoder JSON. O token NUNCA vai para o repositório — Railway
 * variable.
 */
public class AxiomHttpAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final long POLL_TIMEOUT_MS = 500L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_WARN_BODY_CHARS = 200;
    /** Throttle do WARN de ingest: 1º aviso + 1 a cada intervalo se o erro
     *  persistir — evita poluir o console estruturado (e o drain) quando o
     *  Axiom/dataset está fora por horas (ex.: 404/403 contínuo a cada lote). */
    private static final long WARN_THROTTLE_NANOS = Duration.ofMinutes(5).toNanos();

    private String url;
    private String token;
    private int maxQueueSize = 2000;
    private int batchSize = 50;
    private Encoder<ILoggingEvent> encoder;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastIngestWarnNanos = new AtomicLong();
    private BlockingQueue<ILoggingEvent> queue;
    private volatile boolean running;
    private Thread worker;
    private HttpClient httpClient;
    private String resolvedToken;

    @Override
    public void start() {
        resolvedToken = resolveToken();
        if (url == null || url.isBlank() || resolvedToken.isBlank()) {
            addWarn("AxiomHttpAppender desabilitado (fail-open): url=" + url
                    + ", token=" + (resolvedToken.isBlank() ? "ausente" : "ok")
                    + " — defina AXIOM_API_TOKEN e o url do ingest em produção.");
            return; // started permanece false → doAppend() é no-op
        }
        queue = new ArrayBlockingQueue<>(maxQueueSize);
        httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        running = true;
        worker = new Thread(this::drainLoop, "axiom-log-drain");
        worker.setDaemon(true);
        worker.start();
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        BlockingQueue<ILoggingEvent> queueRef = this.queue;
        if (queueRef == null) {
            return; // appender não iniciado (fail-open: config ausente)
        }
        // Snapshot do MDC (requestId/traceId/spanId) e throwable no THREAD que
        // logou — o worker é outra thread; sem isso o JSON chegaria sem o MDC.
        try {
            event.prepareForDeferredProcessing();
        } catch (RuntimeException e) {
            // Fail-open: evento segue sem MDC (melhor perder campos que o request).
            addWarn("Falha ao preparar evento de log (MDC não capturado): " + e);
        }
        if (queueRef.offer(event)) {
            return;
        }
        long total = dropped.incrementAndGet();
        if (total == 1 || total % 1000 == 0) {
            addWarn("Fila do AxiomHttpAppender cheia (max=" + maxQueueSize
                    + ") — descartando eventos. Total descartado: " + total);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (encoder != null) {
            encoder.stop();
        }
        super.stop();
    }

    // ------------------------------------------------------------------
    // Config (Joran → setters JavaBean)
    // ------------------------------------------------------------------

    public void setUrl(String url) {
        this.url = url;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = Math.max(1, maxQueueSize);
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    // ------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------

    private void drainLoop() {
        List<ILoggingEvent> batch = new ArrayList<>(batchSize);
        while (running) {
            try {
                ILoggingEvent event = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (event == null) {
                    if (!batch.isEmpty()) {
                        sendBatchQuietly(batch);
                        batch = new ArrayList<>(batchSize);
                    }
                    continue;
                }
                batch.add(event);
                if (batch.size() >= batchSize) {
                    sendBatchQuietly(batch);
                    batch = new ArrayList<>(batchSize);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Esvaziamento final (stop): best-effort, não bloqueia a aplicação.
        if (!batch.isEmpty()) {
            sendBatchQuietly(batch);
        }
        List<ILoggingEvent> rest = new ArrayList<>(batchSize);
        ILoggingEvent evt;
        while ((evt = queue.poll()) != null) {
            rest.add(evt);
            if (rest.size() >= batchSize) {
                sendBatchQuietly(rest);
                rest = new ArrayList<>(batchSize);
            }
        }
        if (!rest.isEmpty()) {
            sendBatchQuietly(rest);
        }
    }

    private void sendBatchQuietly(List<ILoggingEvent> batch) {
        try {
            sendBatch(batch);
        } catch (Exception e) {
            addWarn("Falha ao enviar lote para o Axiom (eventos perdidos): " + e);
        }
    }

    private void sendBatch(List<ILoggingEvent> batch) throws Exception {
        StringBuilder body = new StringBuilder(batch.size() * 256);
        body.append('[');
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            byte[] json = encoder.encode(batch.get(i));
            body.append(new String(json, StandardCharsets.UTF_8));
        }
        body.append(']');

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + resolvedToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            warnIngestThrottled("Axiom ingest respondeu " + response.statusCode() + ": "
                    + truncate(response.body(), MAX_WARN_BODY_CHARS));
        }
    }

    /** WARN de ingest com throttle: 1º aviso imediato + 1 a cada
     *  {@link #WARN_THROTTLE_NANOS} se o erro persistir. Evita que um drain
     *  fora do ar (404/403 contínuo) polua o console a cada lote (~10s). */
    private void warnIngestThrottled(String message) {
        long now = System.nanoTime();
        long last = lastIngestWarnNanos.get();
        if (last == 0L || now - last >= WARN_THROTTLE_NANOS) {
            if (lastIngestWarnNanos.compareAndSet(last, now)) {
                addWarn(message);
            }
        }
    }

    private String resolveToken() {
        if (token != null && !token.isBlank() && !token.startsWith("${")) {
            return token;
        }
        String env = System.getenv("AXIOM_API_TOKEN");
        return env == null ? "" : env;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }
}
