package com.foxcash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
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
 * Spec 137 (T1.4) — rate limit com Redis REAL: flood em rota limitada por IP →
 * 429 (janela fixa via script Lua atômico, Spec 43).
 *
 * <p>Requer Docker (Testcontainers Redis) — sem Docker o teste é pulado
 * (assumption). Teto reduzido ({@code max-requests=3}) para o burst ser rápido.
 */
@Tag("integration")
@TestPropertySource(properties = {
        "foxcash.security.rate-limit.enabled=true",
        "foxcash.security.rate-limit.store=redis",
        "foxcash.security.rate-limit.max-requests=3",
        "foxcash.security.rate-limit.window-seconds=60",
        "spring.data.redis.timeout=1s"
})
class RateLimitRedisIntegrationTest extends IntegrationTestBase {

    static final GenericContainer<?> REDIS = maybeStartRedis();

    private static GenericContainer<?> maybeStartRedis() {
        if (!DOCKER_AVAILABLE) {
            return null;
        }
        GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redis.start();
        Runtime.getRuntime().addShutdownHook(new Thread(redis::stop));
        return redis;
    }

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(REDIS != null, "Docker indisponível — teste de Redis pulado");
    }

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        if (REDIS != null) {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        }
    }

    @Test
    void burstOnPublicRouteReturns429AfterLimit() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // max-requests=3 → 3 permitidos + 4º → 429 (mesmo IP, mesma rota).
        int allowed = 0;
        int rejected = 0;
        for (int i = 0; i < 5; i++) {
            ResponseEntity<JsonNode> response = rest.exchange("/api/v1/merchants", HttpMethod.POST,
                    new HttpEntity<>(Map.of(
                            "name", "Integração RL " + i,
                            "email", randomEmail(),
                            "password", "Senha-RL-1"), headers),
                    JsonNode.class);
            if (response.getStatusCode().value() == 201) {
                allowed++;
            } else if (response.getStatusCode().value() == 429) {
                rejected++;
            }
        }
        assertThat(allowed).isEqualTo(3);
        assertThat(rejected).isGreaterThanOrEqualTo(1);
    }
}
