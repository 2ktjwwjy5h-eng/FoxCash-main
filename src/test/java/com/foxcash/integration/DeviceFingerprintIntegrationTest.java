package com.foxcash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.foxcash.infrastructure.outbound.persistence.entity.DeviceFingerprintJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.DeviceFingerprintMerchantJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.NotificationOutboxJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.DeviceFingerprintJpaRepository;
import com.foxcash.infrastructure.outbound.persistence.repository.NotificationOutboxJpaRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 141 (auditoria C5) — upsert atômico do device fingerprint + alerta
 * FINGERPRINT_DEVICE_SHARED, no caminho REAL HTTP → service → JPA → Postgres.
 *
 * <p>Dois lojistas se cadastram com o MESMO hash (cenário clássico de risco
 * multi-conta), e o primeiro ainda loga com o mesmo hash: o upsert
 * {@code ON CONFLICT} deve incrementar {@code seen_count} sem duplicar
 * associações, recalcular {@code distinct_merchants} e publicar o alerta no
 * outbox (threshold default 2) — sem bloquear nenhum request.
 */
@Tag("integration")
class DeviceFingerprintIntegrationTest extends IntegrationTestBase {

    @Autowired
    private DeviceFingerprintJpaRepository fingerprintJpaRepository;

    @Autowired
    private NotificationOutboxJpaRepository outboxJpaRepository;

    /** Hash SHA-256 hex válido (64 chars) — estável para os 3 requests. */
    private static final String SAME_HASH = "a".repeat(64);

    @Test
    void sameHashInRegisterLoginUpsertsAtomicallyAndPublishesAlert() {
        // ---- 1. Dois lojistas se cadastram com o MESMO fingerprintHash ----
        String emailA = randomEmail();
        String emailB = randomEmail();
        ResponseEntity<JsonNode> regA = registerWithFingerprint(emailA, "Senha-Integ-1");
        ResponseEntity<JsonNode> regB = registerWithFingerprint(emailB, "Senha-Integ-2");
        assertThat(regA.getStatusCode().value()).isEqualTo(201);
        assertThat(regB.getStatusCode().value()).isEqualTo(201);

        // ---- 2. O lojista A ainda LOGIN com o mesmo hash (3º uso) ----
        ResponseEntity<JsonNode> login = loginWithFingerprint(emailA, "Senha-Integ-1");
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        // ---- 3. Upsert: 1 linha no fingerprint + 2 associações (sem duplicar) ----
        DeviceFingerprintJpaEntity fp = fingerprintJpaRepository.findById(SAME_HASH).orElseThrow();
        assertThat(fp.getSeenCount()).isEqualTo(3); // cadastro A + cadastro B + login A
        assertThat(fp.getDistinctMerchants()).isEqualTo(2); // 2 lojistas distintos

        // ---- 4. Associações: exatamente 2 pares (PK composta — sem duplicar) ----
        List<DeviceFingerprintMerchantJpaEntity> links = fingerprintJpaRepository.findByIdFingerprintHash(SAME_HASH);
        assertThat(links).hasSize(2);

        // ---- 5. Alerta publicado no outbox (FINGERPRINT_DEVICE_SHARED) ----
        List<NotificationOutboxJpaEntity> alerts = outboxJpaRepository.findAll().stream()
                .filter(e -> "FINGERPRINT_DEVICE_SHARED".equals(e.getType()))
                .toList();
        assertThat(alerts).isNotEmpty();
        NotificationOutboxJpaEntity alert = alerts.get(alerts.size() - 1);
        assertThat(alert.getPayload()).contains(SAME_HASH).contains("\"distinctMerchants\":2");

        // ---- 6. Nada bloqueou: os 2 lojistas existem e logam ----
        ResponseEntity<JsonNode> meA = meWithLogin(emailA, "Senha-Integ-1");
        assertThat(meA.getStatusCode().value()).isEqualTo(200);
    }

    private ResponseEntity<JsonNode> registerWithFingerprint(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/v1/merchants",
                new HttpEntity<>(Map.of(
                        "name", "Integração Fingerprint",
                        "email", email,
                        "password", password,
                        "fingerprintHash", SAME_HASH), headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> loginWithFingerprint(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/v1/merchant-auth/login",
                new HttpEntity<>(Map.of(
                        "email", email,
                        "password", password,
                        "fingerprintHash", SAME_HASH), headers),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> meWithLogin(String email, String password) {
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/v1/merchant-auth/login",
                Map.of("email", email, "password", password), JsonNode.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().get("token").asText());
        return rest.exchange("/api/v1/portal/me", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
    }
}
