package com.foxcash.infrastructure.notification;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do signer v2 (Spec 148): formato {@code t=<unix>,v1=<hex>} com HMAC de
 * {@code "<t>.<rawBody>"}, t explícito, validação reproduzível e body-tamper.
 */
class WebhookSignatureV2Test {

    private static final String SECRET = "segredo-do-lojista";

    @Test
    void serializesWithTimestampAndHmacOverTimestampDotBody() {
        long t = 1_720_000_000L;
        String body = "{\"eventId\":\"e1\"}";

        String sig = WebhookSignatureV2.serialize(SECRET, body, t);

        assertThat(sig).startsWith("t=1720000000,v1=");
        assertThat(sig).hasSize("t=1720000000,v1=".length() + 64);
        // HMAC é computado sobre "<t>.<rawBody>" — não sobre o body puro.
        assertThat(sig.substring(sig.indexOf("v1=") + 3)).isEqualTo(hmac(SECRET, t + "." + body));
        assertThat(sig.substring(sig.indexOf("v1=") + 3)).isNotEqualTo(hmac(SECRET, body));
    }

    @Test
    void serializeWithoutTusesCurrentEpochSecond() {
        long before = java.time.Instant.now().getEpochSecond();
        String sig = WebhookSignatureV2.serialize(SECRET, "{}");
        long after = java.time.Instant.now().getEpochSecond();

        long t = Long.parseLong(sig.substring(2, sig.indexOf(",v1=")));
        assertThat(t).isBetween(before, after);
        assertThat(sig).contains(",v1=");
    }

    @Test
    void hmacMatchesManualComputation() {
        String data = "1720000000.{\"eventId\":\"e1\"}";
        assertThat(WebhookSignatureV2.hmac(SECRET, data)).isEqualTo(hmac(SECRET, data));
    }

    @Test
    void bodyTamperInvalidatesSignature() {
        // Critério 2(d) do contrato (Spec 148): mudar o body invalida a assinatura —
        // o HMAC cobre "<t>.<rawBody>", então a assinatura do body original NÃO
        // confere para o body adulterado (e vice-versa).
        long t = 1_720_000_000L;
        String body = "{\"eventId\":\"e1\",\"amount\":50.00}";
        String tampered = "{\"eventId\":\"e1\",\"amount\":999.00}";
        String sig = WebhookSignatureV2.serialize(SECRET, body, t);

        assertThat(sig.substring(sig.indexOf("v1=") + 3)).isEqualTo(hmac(SECRET, t + "." + body));
        assertThat(sig.substring(sig.indexOf("v1=") + 3)).isNotEqualTo(hmac(SECRET, t + "." + tampered));
        assertThat(WebhookSignatureV2.serialize(SECRET, tampered, t))
                .isNotEqualTo(sig);
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
