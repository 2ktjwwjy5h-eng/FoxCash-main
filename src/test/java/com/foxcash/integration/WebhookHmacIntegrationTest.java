package com.foxcash.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 137 (T1.3) — webhook do PSP com assinatura HMAC-SHA256: assinatura
 * válida → processa (passa o filtro de assinatura); ausente/inválida → 401.
 *
 * <p>Liga {@code verify-signature=true} (o perfil dev desliga para o E2E) e usa
 * o segredo fake do dev para reproduzir o HMAC real (hex, {@code X-Psp-Signature}).
 */
@Tag("integration")
@TestPropertySource(properties = {
        "foxcash.psp.webhook.verify-signature=true",
        "foxcash.psp.webhook.secret=dev-only-psp-webhook-secret"
})
class WebhookHmacIntegrationTest extends IntegrationTestBase {

    private static final String SECRET = "dev-only-psp-webhook-secret";

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResponseEntity<String> postWebhook(String body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set("X-Psp-Signature", signature);
        }
        return rest.exchange("/webhooks/pix", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void missingSignatureRejectsWith401() {
        String body = "{\"eventId\":\"evt-1\",\"externalReference\":\"E0000\",\"status\":\"PAID\"}";
        assertThat(postWebhook(body, null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void invalidSignatureRejectsWith401() {
        String body = "{\"eventId\":\"evt-2\",\"externalReference\":\"E0000\",\"status\":\"PAID\"}";
        assertThat(postWebhook(body, "deadbeef").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void validSignaturePassesHmacGateAndReachesHandler() throws Exception {
        // Corpo com referência desconhecida: o HMAC VÁLIDO passa o filtro de
        // assinatura e o handler devolve 404 (transação inexistente) — NUNCA 401.
        String body = "{\"eventId\":\"evt-it-hmac-1\",\"externalReference\":\"E00000000000000000000000000000000\","
                + "\"status\":\"PAID\",\"settledAt\":\"" + Instant.now() + "\",\"amount\":50.00}";
        ResponseEntity<String> response = postWebhook(body, sign(body));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
