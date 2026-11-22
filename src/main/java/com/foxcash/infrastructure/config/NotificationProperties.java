package com.foxcash.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao da entrega de notificacoes/webhooks de saida (Spec 35) e do
 * token de recuperacao de senha (Spec 45): maximo de tentativas, base do
 * backoff exponencial, protecao SSRF (auditoria de seguranca 2026-08-05) e o
 * bloco {@code reset-token} (entrega do token de reset por e-mail/log).
 */
@ConfigurationProperties("foxcash.notification")
public class NotificationProperties {

    /** Maximo de tentativas de entrega antes de marcar FAILED (default 5). */
    private int maxAttempts = 5;

    /** Base do backoff exponencial em ms: next = base * 2^attempt (default 30s). */
    private long backoffBaseMs = 30_000;

    /**
     * Permite URLs de webhook de saida em loopback/endereco privado (dev/testes
     * com receptor local). Default {@code false} = seguro: bloqueia SSRF (rede
     * interna/metadata cloud) — em producao nunca deve ser {@code true}.
     */
    private boolean allowLoopback = false;

    /**
     * Tamanho maximo do lote processado por execucao do dispatcher (auditoria
     * 2026-08-05): limita a duracao de cada run — cada notificacao e processada
     * na propria transacao, entao o lote nao prende uma conexao do pool por
     * longos periodos. Spec 65: default 500; configurável por env.
     */
    private int maxBatchSize = 500;

    /**
     * Teto absoluto do lote por execucao do dispatcher (Etapa 1, pos-incidente
     * Fase 2A 2026-08-09; Spec 65 — meio-termo): limita a duracao do pior caso
     * de uma run (500 eventos × 10s de timeout HTTP) e o tamanho da query. NAO
     * deriva mais do lock ShedLock (era PT40M/10s = 240): com o lock reduzido
     * para PT5M, uma run pode durar mais que o lock — o proximo ciclo assume o
     * lock e reentrega o restante, coberto por at-least-once + idempotencia do
     * receptor ({@code X-FoxCash-Event-Id}). Regra de ouro: integridade > throughput.
     */
    private static final int MAX_BATCH_SIZE_SAFE_LIMIT = 500;

    /**
     * Maximo de lotes processados por aquisicao de lock (Spec 65 — Fase 3): o
     * dispatcher drena em loop (findPendingDue -> processa -> re-consulta) ate
     * esvaziar o backlog ou atingir este teto — dentro do MESMO lock ShedLock.
     * Default 10; configurável por env.
     */
    private int maxBatchesPerRun = 10;

    /**
     * Worker do outbox (Spec 134 — modo dreno): {@code true} = o
     * {@code NotificationDispatcherService} processa o backlog do outbox DB
     * (caminho atual). Com a fila RabbitMQ ativa, desligar o worker
     * ({@code false}) após o backlog zerar — a entrega passa a ser pela fila;
     * o outbox DB continua como fonte da verdade (fail-safe). Rollback =
     * religar ({@code true}).
     */
    private boolean workerEnabled = true;

    /** Entrega do token de recuperacao de senha (Spec 45). */
    private ResetToken resetToken = new ResetToken();

    /** Webhook de OPERADOR (Spec 53): destino de eventos de plataforma (ex.: JOB_FAILED). */
    private String operatorWebhookUrl = "";

    /** Segredo HMAC do webhook de operador (Spec 53) — obrigatorio junto com a URL. */
    private String operatorWebhookSecret = "";

    /** Profiles ativos — usado no fail-fast de seguranca do {@code reset-token}. */
    @Value("${spring.profiles.active:}")
    private String activeProfiles = "";

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBackoffBaseMs() {
        return backoffBaseMs;
    }

    public void setBackoffBaseMs(long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs;
    }

    public boolean isAllowLoopback() {
        return allowLoopback;
    }

    public void setAllowLoopback(boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public ResetToken getResetToken() {
        return resetToken;
    }

    public void setResetToken(ResetToken resetToken) {
        this.resetToken = resetToken;
    }

    public String getOperatorWebhookUrl() {
        return operatorWebhookUrl;
    }

    public void setOperatorWebhookUrl(String operatorWebhookUrl) {
        this.operatorWebhookUrl = operatorWebhookUrl;
    }

    public String getOperatorWebhookSecret() {
        return operatorWebhookSecret;
    }

    public void setOperatorWebhookSecret(String operatorWebhookSecret) {
        this.operatorWebhookSecret = operatorWebhookSecret;
    }

    /** True quando o webhook de operador esta configurado (URL preenchida) — Spec 53. */
    public boolean isOperatorWebhookConfigured() {
        return operatorWebhookUrl != null && !operatorWebhookUrl.isBlank();
    }

    /** Exposição para testes do fail-fast (profiles ativos). */
    public void setActiveProfiles(String activeProfiles) {
        this.activeProfiles = activeProfiles;
    }

    /**
     * Fail-fast de seguranca (Spec 45): o token de reset e credencial de conta —
     * em producao nunca pode ir para logs. {@code deliverer=log} e aceito apenas
     * fora do profile {@code prod}.
     */
    @PostConstruct
    void validate() {
        boolean prod = java.util.Arrays.stream(activeProfiles.split(","))
                .map(String::trim)
                .anyMatch("prod"::equalsIgnoreCase);
        if (prod && "log".equalsIgnoreCase(resetToken.getDeliverer())) {
            throw new IllegalStateException(
                    "foxcash.notification.reset-token.deliverer=log e proibido em producao: o token de "
                            + "recuperacao e credencial de conta e nunca vai para logs (Spec 45). "
                            + "Configure deliverer=smtp (FOXCASH_RESET_TOKEN_DELIVERER=smtp).");
        }
        // Spec 53: URL e segredo do webhook de operador sao configurados JUNTOS.
        boolean operatorUrlSet = operatorWebhookUrl != null && !operatorWebhookUrl.isBlank();
        boolean operatorSecretSet = operatorWebhookSecret != null && !operatorWebhookSecret.isBlank();
        if (operatorUrlSet != operatorSecretSet) {
            throw new IllegalStateException(
                    "foxcash.notification.operator-webhook-url e operator-webhook-secret devem ser "
                            + "configurados juntos (Spec 53): FOXCASH_NOTIFICATION_OPERATOR_WEBHOOK_URL + "
                            + "FOXCASH_NOTIFICATION_OPERATOR_WEBHOOK_SECRET.");
        }
        // Etapa 1 (pos-incidente Fase 2A) + Spec 65 (meio-termo): teto ABSOLUTO
        // do lote por run — nao deriva mais do lock (PT5M). O lock curto garante
        // takeover rapido de batch preso; overlap entre ciclos e coberto por
        // at-least-once + idempotencia do receptor. O teto limita o pior caso da
        // run e a query. Fail-fast: integridade > throughput.
        if (maxBatchSize > MAX_BATCH_SIZE_SAFE_LIMIT) {
            throw new IllegalStateException(
                    "foxcash.notification.max-batch-size=" + maxBatchSize + " excede o teto absoluto "
                            + MAX_BATCH_SIZE_SAFE_LIMIT + " eventos por run (limita a duracao do pior "
                            + "caso e o tamanho da query). Configure FOXCASH_NOTIFICATION_MAX_BATCH_SIZE <= "
                            + MAX_BATCH_SIZE_SAFE_LIMIT + ".");
        }
        // Spec 65: o teto de lotes por run precisa ser >= 1 (loop do dispatcher).
        if (maxBatchesPerRun < 1) {
            throw new IllegalStateException(
                    "foxcash.notification.max-batches-per-run=" + maxBatchesPerRun
                            + " invalido: o dispatcher processa no minimo 1 lote por run. "
                            + "Configure FOXCASH_NOTIFICATION_MAX_BATCHES_PER_RUN >= 1.");
        }
    }

    /** Bloco {@code foxcash.notification.reset-token.*} (Spec 45). */
    public static class ResetToken {

        /** Canal de entrega: {@code log} (dev/testes) | {@code smtp} (producao). */
        private String deliverer = "log";

        /** URL base do frontend do portal (para o link de redefinição no e-mail). */
        private String baseUrl = "http://localhost:5173";

        /** Configuracao SMTP (JavaMailSender) — {@code email.*}. */
        private Email email = new Email();

        public String getDeliverer() {
            return deliverer;
        }

        public void setDeliverer(String deliverer) {
            this.deliverer = deliverer;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Email getEmail() {
            return email;
        }

        public void setEmail(Email email) {
            this.email = email;
        }
    }

    /** Configuracao SMTP (Spec 45) — {@code foxcash.notification.reset-token.email.*}. */
    public static class Email {

        private String host = "";
        private int port = 587;
        private String username = "";
        private String password = "";
        private String from = "";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }
    }
}
