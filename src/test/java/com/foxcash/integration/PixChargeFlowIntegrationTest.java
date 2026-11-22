package com.foxcash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 137 (T1.1) — fluxo crítico: criação de cobrança Pix → idempotência →
 * webhook PAID → ledger/wallet atualizados (zona sagrada), no caminho REAL
 * HTTP → controller → service → JPA → Postgres.
 */
@Tag("integration")
class PixChargeFlowIntegrationTest extends IntegrationTestBase {

    @Test
    void chargeCreationIsIdempotentAndPaidWebhookUpdatesWalletAndLedger() {
        // ---- 1. Registra um lojista real (POST /api/v1/merchants — permitAll) ----
        String email = randomEmail();
        ResponseEntity<JsonNode> reg = rest.postForEntity("/api/v1/merchants",
                Map.of("name", "Integração Pix", "email", email, "password", "Senha-Integ-1"),
                JsonNode.class);
        assertThat(reg.getStatusCode().value()).isEqualTo(201);
        String apiKey = reg.getBody().get("apiKey").asText();
        String merchantId = reg.getBody().get("id").asText();
        assertThat(apiKey).startsWith("mch_");

        // ---- 2. Cria a cobrança com idempotencyKey (Spec 28) ----
        String idempotencyKey = randomIdempotencyKey();
        HttpHeaders apiHeaders = new HttpHeaders();
        apiHeaders.setContentType(MediaType.APPLICATION_JSON);
        apiHeaders.set("X-Api-Key", apiKey);

        ResponseEntity<JsonNode> created = rest.exchange("/api/v1/foxcash/pix/cob", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "merchantId", merchantId,
                        "amount", 150.0,
                        "buyerId", "buyer-it-001",
                        "expirationSeconds", 3600,
                        "idempotencyKey", idempotencyKey), apiHeaders),
                JsonNode.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String transactionId = created.getBody().get("transactionId").asText();
        String externalReference = created.getBody().get("externalReference").asText();
        assertThat(externalReference).isNotBlank();

        // ---- 3. Replay com a MESMA chave → MESMA transação (1 única no banco) ----
        ResponseEntity<JsonNode> replay = rest.exchange("/api/v1/foxcash/pix/cob", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "merchantId", merchantId,
                        "amount", 150.0,
                        "expirationSeconds", 3600,
                        "idempotencyKey", idempotencyKey), apiHeaders),
                JsonNode.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getBody().get("transactionId").asText()).isEqualTo(transactionId);

        // ---- 4. Webhook PAID (Spec 23/27 — caminho real do PSP) ----
        ResponseEntity<String> paid = rest.postForEntity("/webhooks/pix",
                Map.of(
                        "eventId", "evt-it-" + transactionId,
                        "externalReference", externalReference,
                        "status", "PAID",
                        "settledAt", Instant.now().toString(),
                        "amount", new BigDecimal("150.00")),
                String.class);
        assertThat(paid.getStatusCode().value()).isEqualTo(204);

        // ---- 5. Transação APPROVED + paidAt (zona sagrada — status consistente) ----
        ResponseEntity<JsonNode> tx = rest.exchange("/api/v1/transactions/" + transactionId,
                HttpMethod.GET, new HttpEntity<>(apiHeaders), JsonNode.class);
        assertThat(tx.getStatusCode().value()).isEqualTo(200);
        assertThat(tx.getBody().get("status").asText()).isEqualTo("APPROVED");
        assertThat(tx.getBody().get("paidAt").isNull()).isFalse();

        // ---- 6. Wallet creditado (Spec 14/27 — saldo = valor aprovado) ----
        ResponseEntity<JsonNode> wallet = rest.exchange("/api/v1/portal/wallet/balance",
                HttpMethod.GET,
                new HttpEntity<>(portalHeadersFromLogin(email, "Senha-Integ-1")),
                JsonNode.class);
        assertThat(wallet.getStatusCode().value()).isEqualTo(200);
        assertThat(new BigDecimal(wallet.getBody().get("walletBalance").asText()))
                .isEqualByComparingTo(new BigDecimal("150.00"));
    }

    private HttpHeaders portalHeadersFromLogin(String email, String password) {
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/v1/merchant-auth/login",
                Map.of("email", email, "password", password), JsonNode.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().get("token").asText());
        return headers;
    }
}
