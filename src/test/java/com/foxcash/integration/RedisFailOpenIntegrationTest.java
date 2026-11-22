package com.foxcash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 137 (T1.5) — fail-open do rate limit com Redis (ADR-010): com o Redis
 * REAL funcionando o 429 corta; com o container DERRUBADO a app continua
 * respondendo (o {@code RedisRateLimitStore} permite a requisição — o gateway
 * nunca para por infra auxiliar).
 *
 * <p>Requer Docker — sem Docker o teste é pulado (assumption). Usa um container
 * Redis PRÓPRIO (não compartilha com o RateLimitRedisIntegrationTest) para poder
 * derrubá-lo no meio do teste sem afetar os demais (contexto separado).
 */
@Tag("integration")
@TestPropertySource(properties = {
        "foxcash.security.rate-limit.enabled=true",
        "foxcash.security.rate-limit.store=redis",
        "foxcash.security.rate-limit.max-requests=3",
        "foxcash.security.rate-limit.window-seconds=60",
        "spring.data.redis.timeout=1s",
        // Contexto próprio deste teste (chave de cache distinta) — não compartilha
        // o contexto com o RateLimitRedisIntegrationTest.
        "foxcash.test.fail-open-ctx=true"
})
class RedisFailOpenIntegrationTest extends IntegrationTestBase {

    static final GenericContainer<?> REDIS = maybeStartRedis();

    private static GenericContainer<?> maybeStartRedis() {
        if (!DOCKER_AVAILABLE) {
            return null;
        }
        GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redis.start();
        return redis;
    }

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(REDIS != null, "Docker indisponível — teste de Redis pulado");
    }

    @AfterAll
    static void stopRedis() {
        if (REDIS != null && REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        if (REDIS != null) {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        }
    }

    private int registerMerchant() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.exchange("/api/v1/merchants", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "Integração FO",
                        "email", randomEmail(),
                        "password", "Senha-FO-1"), headers),
                JsonNode.class);
        return response.getStatusCode().value();
    }

    @Test
    void appRespondsWhenRedisGoesDown() {
        // ---- 1. Redis UP → 429 corta o flood (prova que o store Redis está vivo) ----
        int rejected = 0;
        for (int i = 0; i < 5; i++) {
            if (registerMerchant() == 429) {
                rejected++;
            }
        }
        assertThat(rejected).isGreaterThanOrEqualTo(1);

        // ---- 2. Redis DERRUBADO → fail-open (ADR-010): a app CONTINUA respondendo.
        // O RedisRateLimitStore permite a requisição (catch RuntimeException → true)
        // e o endpoint responde 201 (nunca 429, nunca 500 por infra auxiliar).
        Assumptions.assumeTrue(REDIS != null, "container de Redis ausente");
        REDIS.stop();

        int statusAfterDown = registerMerchant();
        assertThat(statusAfterDown).isEqualTo(201);
    }
}
