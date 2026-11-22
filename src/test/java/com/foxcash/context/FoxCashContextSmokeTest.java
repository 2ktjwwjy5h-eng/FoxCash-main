package com.foxcash.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.domain.port.out.AlertCounterStore;
import com.foxcash.domain.port.out.RateLimitStore;
import com.foxcash.infrastructure.security.InMemoryAlertCounterStore;
import com.foxcash.infrastructure.security.InMemoryRateLimitStore;
import com.foxcash.infrastructure.security.RequestBodyInspectionFilter;
import com.foxcash.infrastructure.security.WebhookSignatureFilter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 4 — smoke de contexto completo (Spec 68).
 *
 * <p>Sobe a app REAL com o {@code application.yml} completo (perfil {@code dev} +
 * Postgres): Flyway no-op, Hibernate validate, seed idempotente, web server em
 * porta aleatória. Valida a FIAÇÃO do contexto (beans-chave presentes e do tipo
 * esperado) e o health UP — pega a classe de erro do L10 (2 beans do mesmo tipo
 * → boot falha) que a suíte unitária não cobre.
 *
 * <p><b>Pré-requisito:</b> Postgres local (Spec 1) — serviço
 * {@code postgresql-x64-18} na porta 5432, banco {@code nyxcash} (credenciais
 * default do application.yml — o banco NÃO muda no rebrand, Spec 140). Sem o
 * banco, o boot falha (a falha é a sentinela).
 *
 * <p>FORA do {@code mvn test} default (surefire {@code excludedGroups=context}
 * — o job de teste do CI não tem banco). Roda com:
 * {@code mvn test -Pcontext-tests} (local com Postgres e no CI).
 */
@Tag("context")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Fixa os stores para asserções determinísticas (independe de env vars
        // no runner local/CI — o smoke valida FIAÇÃO + health, não a seleção
        // condicional, que é coberta na camada 2):
        properties = {
                "foxcash.security.alert.store=memory",
                "foxcash.security.rate-limit.store=memory" })
@ActiveProfiles("dev")
class FoxCashContextSmokeTest {

    @Autowired
    SecurityAlertPublisher securityAlertPublisher;

    @Autowired
    AlertCounterStore alertCounterStore;

    @Autowired
    RateLimitStore rateLimitStore;

    @Autowired
    RequestBodyInspectionFilter bodyInspectionFilter;

    @Autowired
    WebhookSignatureFilter webhookSignatureFilter;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestRestTemplate rest;

    @Test
    void contextLoadsAndKeyBeansAreWired() {
        assertThat(securityAlertPublisher).isNotNull();
        assertThat(alertCounterStore).isInstanceOf(InMemoryAlertCounterStore.class);
        assertThat(rateLimitStore).isInstanceOf(InMemoryRateLimitStore.class);
        assertThat(bodyInspectionFilter).isNotNull();
        assertThat(webhookSignatureFilter).isNotNull();
        assertThat(objectMapper).isNotNull();
    }

    @Test
    void healthEndpointIsUp() {
        var resp = rest.getForEntity("/actuator/health", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"UP\"");
    }
}
