package com.foxcash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 137 (T1.2) — autenticação merchant (login → token → acesso) e RBAC
 * master (403 sem role de master) no caminho real.
 */
@Tag("integration")
class AuthRbacIntegrationTest extends IntegrationTestBase {

    @Test
    void merchantLoginGrantsAccessAndMasterRoutesRequireMasterRole() {
        // ---- 1. Registra + login do lojista ----
        String email = randomEmail();
        ResponseEntity<JsonNode> reg = rest.postForEntity("/api/v1/merchants",
                Map.of("name", "Integração Auth", "email", email, "password", "Senha-Auth-1"),
                JsonNode.class);
        assertThat(reg.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<JsonNode> login = rest.postForEntity("/api/v1/merchant-auth/login",
                Map.of("email", email, "password", "Senha-Auth-1"), JsonNode.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String token = login.getBody().get("token").asText();
        assertThat(token).isNotBlank();

        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(token);

        // ---- 2. Token válido acessa /api/v1/portal/me ----
        ResponseEntity<JsonNode> me = rest.exchange("/api/v1/portal/me", HttpMethod.GET,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().get("email").asText()).isEqualTo(email);

        // ---- 3. Sem token → 401 ----
        assertThat(rest.getForEntity("/api/v1/portal/me", JsonNode.class).getStatusCode().value())
                .isEqualTo(401);

        // ---- 4. Token INVÁLIDO → 401 ----
        HttpHeaders bogus = new HttpHeaders();
        bogus.setBearerAuth("ses_invalido");
        assertThat(rest.exchange("/api/v1/portal/me", HttpMethod.GET, new HttpEntity<>(bogus), JsonNode.class)
                .getStatusCode().value()).isEqualTo(401);

        // ---- 5. RBAC master: token de LOJISTA em rota ROLE_MASTER → 403 ----
        ResponseEntity<JsonNode> master = rest.exchange("/api/v1/master/transactions", HttpMethod.GET,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(master.getStatusCode().value()).isEqualTo(403);

        // ---- 6. Role privilegiada REAL (ROLE_ADMIN via chave admin) acessa rota
        // admin — prova positiva do RBAC (não depende do seed de master, que só
        // cria em banco vazio: no Postgres nativo local a tabela já tem masters). ----
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("X-Api-Key", "dev-only-admin-api-key");
        ResponseEntity<JsonNode> adminOk = rest.exchange("/api/v1/admin/merchants", HttpMethod.GET,
                new HttpEntity<>(adminHeaders), JsonNode.class);
        assertThat(adminOk.getStatusCode().value()).isEqualTo(200);
    }
}
