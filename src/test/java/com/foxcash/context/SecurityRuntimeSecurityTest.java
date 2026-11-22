package com.foxcash.context;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 131 (C2/C3/C5 + T0.3) — segurança de runtime provada por teste contra a
 * app REAL (mesmo padrão do smoke da Spec 68):
 *
 * <ul>
 *   <li><b>C2 — Actuator mínimo:</b> {@code /actuator/health} público (200);
 *       demais endpoints ({@code /prometheus}, {@code /env}, {@code /beans},
 *       {@code /metrics}) SEM auth → 401 (nunca 200); {@code /prometheus} com
 *       ROLE_ADMIN → 200.</li>
 *   <li><b>C3 — Headers de segurança:</b> presentes nas rotas principais
 *       (nosniff, frame DENY, CSP, Referrer-Policy, Cache-Control).</li>
 *   <li><b>C1/T0.3 — Sem senha gerada no boot:</b> captura o log do boot
 *       (appender no ROOT antes do contexto) e asserta a AUSÊNCIA de
 *       "Using generated security password" (fix: exclusão do
 *       {@code UserDetailsServiceAutoConfiguration} no application.yml).</li>
 * </ul>
 *
 * <p>FORA do {@code mvn test} default — {@code @Tag("context")} (exige Postgres,
 * perfil dev). Roda com {@code mvn test -Pcontext-tests} (local e CI).
 */
@Tag("context")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "foxcash.security.require-https=false",
                "foxcash.security.alert.store=memory",
                "foxcash.security.rate-limit.store=memory",
                // Boot 3.5 (Spec 130): o spring-boot-test-autoconfigure DESLIGA os
                // exporters de métrica por default em @SpringBootTest
                // (DisableObservabilityContextCustomizer →
                // management.defaults.metrics.export.enabled=false, chave
                // test.observability.auto). Sem o re-enable o
                // PrometheusMeterRegistry não existe e /actuator/prometheus
                // responde 404 (endpoint nem registrado) — verificado no
                // ConditionEvaluationReport. O teste é de SEGURANÇA (role
                // ROLE_ADMIN no endpoint), não de métricas: reabilita APENAS o
                // export do prometheus (o default=false continua valendo para
                // os demais exporters).
                "management.prometheus.metrics.export.enabled=true" })
@ActiveProfiles("dev")
class SecurityRuntimeSecurityTest {

    /** Captura TODOS os eventos de log do boot (anexado antes do contexto Spring carregar). */
    private static final ListAppender<ILoggingEvent> BOOT_LOG = new ListAppender<>();

    static {
        BOOT_LOG.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME))
                .addAppender(BOOT_LOG);
    }

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", "dev-only-admin-api-key");
        return headers;
    }

    // ---------- C2 — Actuator com exposição mínima ----------

    @Test
    void actuatorHealthIsPublic() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void actuatorSensitiveEndpointsRequireAuth() {
        List<String> endpoints = List.of("/actuator/prometheus", "/actuator/env", "/actuator/beans", "/actuator/metrics");
        for (String endpoint : endpoints) {
            ResponseEntity<String> noAuth = rest.getForEntity(endpoint, String.class);
            int status = noAuth.getStatusCode().value();
            assertThat(status)
                    .as("Endpoint %s sem auth deve ser 401/403, nunca 200", endpoint)
                    .isIn(401, 403);
        }
    }

    @Test
    void actuatorPrometheusAllowedForAdminRole() {
        ResponseEntity<String> withAdmin = rest.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), String.class);
        assertThat(withAdmin.getStatusCode().value()).isEqualTo(200);
    }

    // ---------- C3 — Headers de segurança ----------

    @Test
    void securityHeadersPresentOnMainRoutes() {
        // Rota pública (actuator health) — mesma cadeia de headers do SecurityConfig.
        ResponseEntity<String> publicRoute = rest.getForEntity("/actuator/health", String.class);
        assertSecurityHeaders(publicRoute.getHeaders());

        // Rota autenticada de API — headers idênticos.
        ResponseEntity<String> apiRoute = rest.exchange("/api/v1/admin/merchants", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), String.class);
        assertThat(apiRoute.getStatusCode().value()).isEqualTo(200);
        assertSecurityHeaders(apiRoute.getHeaders());
    }

    private void assertSecurityHeaders(HttpHeaders headers) {
        // Defaults do Spring Security (headers → nosniff/frame DENY/no-store).
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        // CSP explícita do SecurityConfig (API pura: default-src 'none').
        String csp = headers.getFirst("Content-Security-Policy");
        assertThat(csp).isNotNull().contains("default-src 'none'");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        // HSTS só quando require-https=true (dev/test = false) — não deve aparecer aqui.
        assertThat(headers.getFirst("Strict-Transport-Security")).isNull();
    }

    // ---------- C1 / T0.3 — Sem senha gerada no boot ----------

    @Test
    void bootLogDoesNotContainGeneratedSecurityPassword() {
        String logs = BOOT_LOG.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logs)
                .as("O log do boot não pode conter 'Using generated security password' (Spec 131 C1)")
                .doesNotContain("Using generated security password");
    }
}
