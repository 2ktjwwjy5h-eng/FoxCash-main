package com.foxcash.integration;

import com.foxcash.application.service.WebhookEventPublisher;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.infrastructure.outbound.persistence.entity.NotificationOutboxJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.NotificationOutboxJpaRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 134 (T5.1) — fail-safe ADR-010 com o broker INDISPONÍVEL: com a fila
 * ligada ({@code foxcash.rabbit.enabled=true}) mas sem broker alcançável, o
 * {@code OutboxRelay} falha ao publicar (conexão recusada) → captura + WARN →
 * o evento PERMANECE no outbox DB ({@code PENDING}), jamais perdido, e a
 * chamada de origem NÃO quebra — o worker do outbox (modo dreno) entrega depois.
 *
 * <p>"Broker down" é simulado apontando para uma porta TCP garantidamente
 * fechada (aberta e liberada) — conexão recusada imediatamente, sem precisar
 * derrubar um broker real no meio do teste.
 */
@Tag("integration")
class OutboxRelayBrokerDownIntegrationTest extends IntegrationTestBase {

    /** Porta TCP garantidamente fechada (reservada e liberada). */
    private static final int CLOSED_PORT = findClosedPort();

    private static int findClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao reservar porta para simular broker down", e);
        }
    }

    @DynamicPropertySource
    static void brokerDownProps(DynamicPropertyRegistry registry) {
        // Fila LIGADA, porém broker inacessível (conexão recusada imediatamente).
        registry.add("foxcash.rabbit.enabled", () -> "true");
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> String.valueOf(CLOSED_PORT));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @Autowired
    private WebhookEventPublisher eventPublisher;

    @Autowired
    private NotificationOutboxJpaRepository outboxRepo;

    @Autowired
    private TransactionTemplate txTemplate;

    @Test
    void brokerDownKeepsEventInOutboxWithoutLoss() {
        String marker = "broker-down-" + UUID.randomUUID();

        // O relay tenta publicar na fila (AFTER_COMMIT): conexão recusada →
        // fail-safe — WARN + evento fica PENDING no outbox DB; a origem não quebra.
        txTemplate.executeWithoutResult(status ->
                eventPublisher.publish(null, WebhookEventType.JOB_FAILED, Map.of("marker", marker)));

        NotificationOutboxJpaEntity row = findRowByMarker(marker);
        assertThat(row).as("evento deve ter sido gravado no outbox DB (zero perda)")
                .isNotNull();
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getAttempts()).isZero();
    }

    private NotificationOutboxJpaEntity findRowByMarker(String marker) {
        return outboxRepo.findAll().stream()
                .filter(e -> e.getPayload().contains(marker))
                .findFirst()
                .orElse(null);
    }
}
