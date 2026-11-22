package com.foxcash.integration;

import com.foxcash.application.service.WebhookEventPublisher;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.infrastructure.config.RabbitProperties;
import com.foxcash.infrastructure.outbound.persistence.entity.NotificationOutboxJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.NotificationOutboxJpaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.RabbitMQContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 134 (T5.1) — fila RabbitMQ do outbox com broker REAL (Testcontainers).
 *
 * <p>Roda no job {@code integration} do GitHub Actions (runner tem Docker);
 * sem Docker a classe é pulada por assumption (padrão Spec 137). O
 * {@code OutboxRelay} publica na fila após o commit, o {@code OutboxConsumer}
 * consome e entrega via {@code NotificationProcessor} → webhook de OPERADOR
 * (evento de plataforma, merchantId=null) num receptor local — cobre o caminho
 * ponta a ponta: publish → consume → entrega HTTP real → {@code SENT}.
 *
 * <p>Cenários: (1) publish→consume→entrega; (2) retry até sucesso após falhas
 * transitórias; (3) DLQ após esgotar tentativas; (4) zero perda sob carga
 * simulada + p95 de latência fim-a-fim.
 */
@Tag("integration")
class OutboxRabbitIntegrationTest extends IntegrationTestBase {

    /** RabbitMQ real (Testcontainers) — singleton compartilhado da classe. */
    private static final RabbitMQContainer RABBIT = maybeStartRabbit();

    private static RabbitMQContainer maybeStartRabbit() {
        if (!DOCKER_AVAILABLE) {
            return null;
        }
        RabbitMQContainer container = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
                .withExposedPorts(5672);
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        return container;
    }

    /** Receptor local do webhook de OPERADOR (Spec 53) — entrega HTTP real. */
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final AtomicReference<IntUnaryOperator> RULE =
            new AtomicReference<>(index -> 200);
    private static final int RECEIVER_PORT = startReceiver();

    private static int startReceiver() {
        try {
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/hook", exchange -> {
                exchange.getRequestBody().readAllBytes();
                int status = RULE.get().applyAsInt(REQUEST_COUNT.incrementAndGet());
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
            return server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao subir receptor local do webhook", e);
        }
    }

    @DynamicPropertySource
    static void rabbitProps(DynamicPropertyRegistry registry) {
        if (RABBIT == null) {
            return;
        }
        registry.add("foxcash.rabbit.enabled", () -> "true");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getAmqpPort());
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // Worker do outbox DB desligado: a entrega acontece SÓ pelo caminho da fila.
        registry.add("foxcash.notification.worker-enabled", () -> "false");
        // Evento de plataforma (merchantId=null) → webhook de operador → receptor local.
        registry.add("foxcash.notification.operator-webhook-url",
                () -> "http://127.0.0.1:" + RECEIVER_PORT + "/hook");
        registry.add("foxcash.notification.operator-webhook-secret", () -> "test-secret");
        // Retry rápido no CI (o default de produção é 1s/2x/30s).
        registry.add("foxcash.rabbit.max-attempts", () -> "3");
        registry.add("foxcash.rabbit.retry-initial-interval-ms", () -> "200");
        registry.add("foxcash.rabbit.retry-multiplier", () -> "2.0");
        registry.add("foxcash.rabbit.retry-max-interval-ms", () -> "1000");
    }

    @Autowired
    private WebhookEventPublisher eventPublisher;

    @Autowired
    private NotificationOutboxJpaRepository outboxRepo;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RabbitProperties rabbitProperties;

    @Autowired(required = false)
    private RabbitTemplate rabbitTemplate;

    // ------------------------------------------------------------------
    // 1) publish → consume → entrega HTTP real → SENT
    // ------------------------------------------------------------------

    @Test
    void publishConsumeDeliversViaQueueToOperatorWebhook() {
        Assumptions.assumeTrue(RABBIT != null, "Docker indisponível — pula (Spec 137)");
        RULE.set(index -> 200);
        int requestsBefore = REQUEST_COUNT.get();
        double publishedBefore = counter("foxcash_outbox_published_total");
        double consumedBefore = counter("foxcash_outbox_consumed_total");

        UUID eventId = publishPlatformEvent("happy-" + UUID.randomUUID());

        awaitUntil(() -> outboxRepo.findById(eventId)
                .map(e -> "SENT".equals(e.getStatus())).orElse(false));

        NotificationOutboxJpaEntity row = outboxRepo.findById(eventId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("SENT");
        assertThat(row.getType()).isEqualTo(WebhookEventType.JOB_FAILED.name());
        // Entrega HTTP real: o receptor local recebeu exatamente 1 POST.
        assertThat(REQUEST_COUNT.get() - requestsBefore).isEqualTo(1);
        assertThat(counter("foxcash_outbox_published_total") - publishedBefore).isEqualTo(1);
        assertThat(counter("foxcash_outbox_consumed_total") - consumedBefore).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2) retry até sucesso após falhas transitórias (2x 500 → 200)
    // ------------------------------------------------------------------

    @Test
    void retriesThenSucceedsAfterTransientFailures() {
        Assumptions.assumeTrue(RABBIT != null, "Docker indisponível — pula (Spec 137)");
        int baseline = REQUEST_COUNT.get();
        // As 2 primeiras entregas falham (500); a 3ª (dentro do max-attempts=3) sucede.
        RULE.set(index -> (index - baseline) <= 2 ? 500 : 200);

        UUID eventId = publishPlatformEvent("retry-" + UUID.randomUUID());

        awaitUntil(() -> outboxRepo.findById(eventId)
                .map(e -> "SENT".equals(e.getStatus())).orElse(false));

        NotificationOutboxJpaEntity row = outboxRepo.findById(eventId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("SENT");
        // 2 falhas incrementaram attempts no DB; a 3ª entrega marcou SENT.
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(REQUEST_COUNT.get() - baseline).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 3) DLQ — entrega sempre falha → esgota max-attempts → dead-letter
    // ------------------------------------------------------------------

    @Test
    void failedDeliveryGoesToDlqAfterMaxAttempts() {
        Assumptions.assumeTrue(RABBIT != null, "Docker indisponível — pula (Spec 137)");
        Assumptions.assumeTrue(rabbitTemplate != null, "RabbitTemplate indisponível");
        RULE.set(index -> 500);
        double dlqBefore = counter("foxcash_outbox_dlq_total");

        UUID eventId = publishPlatformEvent("dlq-" + UUID.randomUUID());

        // Rejeição SEM requeue após 3 tentativas → cai na DLQ (dead-letter do RabbitConfig).
        Message dlqMessage = awaitDlqMessage(rabbitProperties.getDeadLetterQueue());
        assertThat(dlqMessage).as("mensagem deve cair na DLQ").isNotNull();

        NotificationOutboxJpaEntity row = outboxRepo.findById(eventId).orElseThrow();
        assertThat(row.getStatus()).isNotEqualTo("SENT");
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(counter("foxcash_outbox_dlq_total") - dlqBefore).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 4) zero perda sob carga simulada + p95 de latência fim-a-fim
    // ------------------------------------------------------------------

    @Test
    void zeroLossUnderBurstLoadAndP95UnderTarget() {
        Assumptions.assumeTrue(RABBIT != null, "Docker indisponível — pula (Spec 137)");
        RULE.set(index -> 200);
        int loadEvents = Integer.parseInt(System.getenv().getOrDefault("OUTBOX_LOAD_EVENTS", "300"));
        long p95MaxMs = Long.parseLong(System.getenv().getOrDefault("OUTBOX_P95_MAX_MS", "5000"));
        double publishedBefore = counter("foxcash_outbox_published_total");
        double consumedBefore = counter("foxcash_outbox_consumed_total");
        double dlqBefore = counter("foxcash_outbox_dlq_total");

        // Publica N eventos de plataforma em paralelo; o relay publica na fila
        // (AFTER_COMMIT) e o consumer entrega via webhook de operador.
        ConcurrentHashMap<UUID, Long> publishedAt = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch published = new CountDownLatch(loadEvents);
        for (int i = 0; i < loadEvents; i++) {
            pool.execute(() -> {
                try {
                    txTemplate.executeWithoutResult(status -> {
                        String marker = "load-" + UUID.randomUUID();
                        eventPublisher.publish(null, WebhookEventType.JOB_FAILED,
                                Map.of("marker", marker));
                        publishedAt.put(findRowByMarker(marker).getId(), System.currentTimeMillis());
                    });
                } finally {
                    published.countDown();
                }
            });
        }
        long publishStart = System.nanoTime();
        try {
            assertThat(published.await(30, TimeUnit.SECONDS))
                    .as("publicação dos %d eventos deve concluir em 30s", loadEvents)
                    .isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }

        // Zero perda: TODOS os eventos publicados viram SENT (e nenhum caiu na DLQ).
        awaitUntil(() -> outboxRepo.findAll().stream()
                .filter(e -> publishedAt.containsKey(e.getId()))
                .filter(e -> "SENT".equals(e.getStatus()))
                .count() == loadEvents);

        long[] latencies = publishedAt.entrySet().stream()
                .mapToLong(e -> outboxRepo.findById(e.getKey()).orElseThrow()
                        .getSentAt().toEpochMilli() - e.getValue())
                .sorted().toArray();
        long p95 = latencies[(int) Math.ceil(latencies.length * 0.95) - 1];
        long totalMs = (System.nanoTime() - publishStart) / 1_000_000;
        double throughput = loadEvents / (totalMs / 1000.0);

        System.out.printf("[OutboxRabbit] carga=%d throughput=%.1f ev/s p95=%dms " +
                        "publicados=%.0f consumidos=%.0f dlq=%.0f%n",
                loadEvents, throughput, p95,
                counter("foxcash_outbox_published_total") - publishedBefore,
                counter("foxcash_outbox_consumed_total") - consumedBefore,
                counter("foxcash_outbox_dlq_total") - dlqBefore);

        assertThat(latencies.length).isEqualTo(loadEvents);
        assertThat(counter("foxcash_outbox_published_total") - publishedBefore)
                .isEqualTo(loadEvents);
        assertThat(counter("foxcash_outbox_consumed_total") - consumedBefore)
                .isEqualTo(loadEvents);
        assertThat(counter("foxcash_outbox_dlq_total") - dlqBefore).isZero();
        assertThat(p95).as("p95 fim-a-fim < teto (env OUTBOX_P95_MAX_MS)")
                .isLessThanOrEqualTo(p95MaxMs);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Publica um evento de PLATAFORMA (JOB_FAILED, sem merchant) numa transação real. */
    private UUID publishPlatformEvent(String marker) {
        txTemplate.executeWithoutResult(status ->
                eventPublisher.publish(null, WebhookEventType.JOB_FAILED, Map.of("marker", marker)));
        return findRowByMarker(marker).getId();
    }

    private NotificationOutboxJpaEntity findRowByMarker(String marker) {
        return outboxRepo.findAll().stream()
                .filter(e -> e.getPayload().contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Evento com marcador não encontrado: " + marker));
    }

    private double counter(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter != null ? counter.count() : 0;
    }

    private Message awaitDlqMessage(String queue) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(queue, 500);
            if (message != null) {
                return message;
            }
        }
        return null;
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condição não atingida em 20s");
    }
}
