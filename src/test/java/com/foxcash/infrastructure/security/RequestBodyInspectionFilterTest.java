package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.SecurityAlertPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Testes do {@link RequestBodyInspectionFilter} (Spec 63 — camada app):
 * payloads de ataque (SQLi/XSS/path traversal) no corpo de POST/PUT/PATCH →
 * 403 {@code body_inspection_failed} antes da auth, com alerta
 * {@link SecurityAlertPublisher#recordBodyInspectionBlocked}; corpos legitimos
 * (cobranca/login/cadastro) passam intactos; webhooks do PSP excluidos.
 */
class RequestBodyInspectionFilterTest {

    private BodyInspectionProperties properties;
    private SecurityAlertPublisher securityAlerts;
    private MeterRegistry meterRegistry;
    private RequestBodyInspectionFilter filter;

    @BeforeEach
    void setUp() {
        properties = new BodyInspectionProperties();
        securityAlerts = mock(SecurityAlertPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        filter = new RequestBodyInspectionFilter(properties, new ObjectMapper(), securityAlerts, meterRegistry);
    }

    // ---- Bloqueios (403 body_inspection_failed) ----

    @Test
    void blocksSqliBooleanTautologyWith403() throws Exception {
        MockHttpServletRequest request = post("/api/v1/merchant-auth/login",
                "{\"email\":\"x@example.com' OR 1=1 --\",\"password\":\"senha\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("body_inspection_failed");
        assertThat(chain.getRequest()).isNull();
        verify(securityAlerts).recordBodyInspectionBlocked("/api/v1/merchant-auth/login", "boolean-tautology", "sql_injection");
    }

    @Test
    void blocksUnionSelectWith403() throws Exception {
        MockHttpServletRequest request = post("/api/v1/merchants",
                "{\"name\":\"x\" UNION SELECT username, password FROM users --\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("body_inspection_failed");
        verify(securityAlerts).recordBodyInspectionBlocked("/api/v1/merchants", "union-select", "sql_injection");
    }

    @Test
    void blocksXssScriptTagWith403() throws Exception {
        MockHttpServletRequest request = post("/api/v1/merchants",
                "{\"name\":\"<script>alert(1)</script>\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("body_inspection_failed");
        verify(securityAlerts).recordBodyInspectionBlocked("/api/v1/merchants", "script-tag", "xss");
    }

    @Test
    void blocksPathTraversalWith403() throws Exception {
        MockHttpServletRequest request = post("/api/v1/merchants",
                "{\"file\":\"/etc/passwd\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("body_inspection_failed");
        verify(securityAlerts).recordBodyInspectionBlocked("/api/v1/merchants", "etc-passwd", "path_traversal");
    }

    @Test
    void blocksOnPutAndPatchAsWell() throws Exception {
        MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/api/v1/merchants/abc");
        put.setContent("{\"name\":\"x\" OR 1=1 --\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(put, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void incrementsBodyInspectionBlocksMetricPerPatternAndCategory() throws Exception {
        // Spec 66 (Fase C): cada bloqueio incrementa foxcash.body_inspection.blocks
        // com tags pattern/category (o total = soma das séries).
        String body = "{\"name\":\"x\" UNION SELECT password FROM users --\"}";

        filter.doFilter(post("/api/v1/merchants", body), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(post("/api/v1/merchants", body), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(meterRegistry.get("foxcash.body_inspection.blocks")
                .tag("pattern", "union-select").tag("category", "sql_injection").counter().count())
                .isEqualTo(2.0);
        // Total = soma das séries registradas (por padrão/categoria).
        double total = meterRegistry.get("foxcash.body_inspection.blocks").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        assertThat(total).isEqualTo(2.0);
    }

    // ---- Corpos legitimos passam ----

    @Test
    void allowsLegitChargeBodyAndPreservesIt() throws Exception {
        String body = "{\"amount\":1000,\"description\":\"Cobranca da loja\","
                + "\"payer\":{\"name\":\"Joao da Silva\",\"document\":\"123.456.789-09\"}}";
        MockHttpServletRequest request = post("/api/v1/foxcash/pix/cob", body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(body);
        verify(securityAlerts, never()).recordBodyInspectionBlocked(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allowsLegitMerchantCreationBody() throws Exception {
        String body = "{\"name\":\"Loja Exemplo\",\"email\":\"loja@example.com\","
                + "\"password\":\"Senha-Segura-123\",\"document\":\"12.345.678/0001-90\"}";
        MockHttpServletRequest request = post("/api/v1/merchants", body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    void allowsLegitLoginBodyWithFreeTextEnglishDescription() throws Exception {
        // Calibracao anti-falso-positivo: "select from" adjacente (prosa) NAO
        // bloqueia — o padrao exige um token entre select e from (comando SQL).
        String body = "{\"email\":\"loja@example.com\",\"password\":\"senha\","
                + "\"description\":\"Please select from our catalog today\"}";
        MockHttpServletRequest request = post("/api/v1/merchant-auth/login", body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    // ---- Exclusoes e feature flag ----

    @Test
    void skipsWebhookPspPathsEvenWithAttackPayload() throws Exception {
        // Webhook do PSP NUNCA e sacrificado (critério 6 Spec 63) — a assinatura
        // HMAC e a validacao da app sao os controles.
        MockHttpServletRequest request = post("/webhooks/pix",
                "{\"event\":\"PAID\",\"data\":\"x' OR 1=1 --\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request); // nao re-embrulha
        verify(securityAlerts, never()).recordBodyInspectionBlocked(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsWhenDisabledEvenWithAttackPayload() throws Exception {
        properties.setEnabled(false);
        MockHttpServletRequest request = post("/api/v1/merchants",
                "{\"name\":\"x\" UNION SELECT password FROM users --\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void skipsGetRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/merchants/abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void skipsPostWithEmptyBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchants");
        request.setContent(new byte[0]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void skipsWebhookExcludedPathEvenWithDotDotTraversal() throws Exception {
        // SEC-18 (revisão de segurança 2026-08-11): o casamento de exclusão usa o
        // path NORMALIZADO. /webhooks/../webhooks/pix resolve para /webhooks/pix
        // (container roteia para o controller do webhook) — o filtro NÃO pode
        // inspecionar o payload do PSP (falso positivo de SQLi em reentrega
        // legítima sacrificaria a disponibilidade — critério 6 Spec 63).
        MockHttpServletRequest request = post("/webhooks/../webhooks/pix",
                "{\"event\":\"PAID\",\"data\":\"x' OR 1=1 --\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request); // não re-embrulha
        verify(securityAlerts, never()).recordBodyInspectionBlocked(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpServletRequest post(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
