package com.foxcash.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Spec 137 — Base dos testes de integração reais.
 *
 * <p>Sobe a app COMPLETA ({@code @SpringBootTest(RANDOM_PORT)} — perfil {@code dev},
 * mesmo padrão do smoke da Spec 68) contra infra real:
 *
 * <ul>
 *   <li><b>Docker disponível</b> → PostgreSQL 18 real via Testcontainers
 *       (container singleton compartilhado entre os testes da mesma config —
 *       context caching do Spring, C5) + Redis real nos testes que precisam.</li>
 *   <li><b>Sem Docker</b> (runner local/CI sem engine) → fallback para o Postgres
 *       nativo do ambiente (porta 5432, credenciais do {@code application.yml}),
 *       mesmo padrão da Spec 68; os testes de Redis são pulados por assumption.</li>
 * </ul>
 *
 * <p>Rate limit mantém o DEFAULT do yml (os testes fazem poucas requisições por
 * rota — bem abaixo dos tetos; os testes de Redis re-ligam com {@code store=redis}
 * e teto baixo via {@code @TestPropertySource} da subclasse, sem conflito).
 *
 * <p>FORA do {@code mvn test} default (surefire {@code excludedGroups=context,
 * integration}). Roda com {@code mvn test -Pintegration-tests}.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // HTTPS relaxado (RANDOM_PORT usa HTTP) — mesmo padrão do smoke.
                "foxcash.security.require-https=false",
                // Jobs ShedLock fora da suíte (fluxos testados são síncronos).
                "spring.task.scheduling.enabled=false",
                // Webhooks de saída podem entregar para o receptor local (none).
                "foxcash.notification.allow-loopback=true"
        })
@ActiveProfiles("dev")
public abstract class IntegrationTestBase {

    protected static final boolean DOCKER_AVAILABLE = DockerClientFactory.instance().isDockerAvailable();

    /** Postgres real (Testcontainers) — singleton compartilhado; null sem Docker. */
    private static final PostgreSQLContainer<?> POSTGRES = maybeStartPostgres();

    private static PostgreSQLContainer<?> maybeStartPostgres() {
        if (!DOCKER_AVAILABLE) {
            return null;
        }
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:18-alpine")
                .withDatabaseName("foxcash")
                .withUsername("foxcash")
                .withPassword("foxcash");
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        return container;
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
        // Sem Docker: credenciais nativas do application.yml (localhost:5432).
        // Pool pequeno por instância de teste — evita esgotar o Postgres nativo.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
    }

    @Autowired
    protected TestRestTemplate rest;

    /** E-mail único por execução (evita colisão no índice único em re-runs). */
    protected static String randomEmail() {
        return "it_" + UUID.randomUUID().toString().substring(0, 12) + "@test.local";
    }

    protected static String randomIdempotencyKey() {
        return "it-key-" + UUID.randomUUID();
    }
}
